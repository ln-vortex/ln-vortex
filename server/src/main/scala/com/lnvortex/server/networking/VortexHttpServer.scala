package com.lnvortex.server.networking

import akka.actor._
import akka.http.scaladsl.Http
import com.lnvortex.core.VortexUtils
import com.lnvortex.server.coordinator.VortexCoordinator
import grizzled.slf4j.Logging
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.tor.TorController

import java.net.InetSocketAddress
import scala.concurrent._
import scala.concurrent.duration.DurationInt

class VortexHttpServer(coordinator: VortexCoordinator)(implicit
    val system: ActorSystem)
    extends StartStopAsync[Unit]
    with Logging {
  implicit val executionContext: ExecutionContext = system.dispatcher

  private val bindingP: Promise[Http.ServerBinding] =
    Promise[Http.ServerBinding]()

  def getBinding: Future[Http.ServerBinding] = bindingP.future

  private val hostAddressP: Promise[InetSocketAddress] =
    Promise[InetSocketAddress]()

  def getHostAddress: Future[InetSocketAddress] = {
    hostAddressP.future
  }

  private val routes = new CoordinatorRoutes(coordinator)

  def currentCoordinator: VortexCoordinator = routes.coordinator

  override def start(): Future[Unit] = {
    val config = coordinator.config

    if (bindingP.isCompleted) {
      logger.info("VortexHttpServer already started")
      Future.unit
    } else {
      val bindAddress = config.listenAddress
      for {
        onionAddress <- config.torParams match {
          case Some(params) =>
            val targets = s"127.0.0.1:${bindAddress.getPort}" +:
              config.targets.map(ip => s"${ip.getHostString}:${ip.getPort}")

            logger.info(
              "Creating tor hidden service to listen on " +
                s"${targets.mkString(",")}")

            TorController
              .setUpHiddenService(
                params.controlAddress,
                params.authentication,
                params.privateKeyPath,
                VortexUtils.getDefaultPort(config.network),
                targets = targets
              )
              .map(Some(_))
          case None => Future.successful(None)
        }
        bind <- Http()
          .newServerAt(bindAddress.getHostName, bindAddress.getPort)
          .bind(routes.topLevelRoute)

        _ <- (config.httpsPortOpt, config.httpOpt) match {
          case (Some(httpsPort), Some(httpConf)) =>
            Http()
              .newServerAt(bindAddress.getHostName, httpsPort)
              .enableHttps(httpConf)
              .bind(routes.topLevelRoute)
          case (Some(_), None) | (None, Some(_)) =>
            Future.failed(new RuntimeException("Invalid https configuration"))
          case (None, None) => Future.unit
        }
      } yield {
        val addr = onionAddress.getOrElse(bind.localAddress)
        bindingP.success(bind)
        hostAddressP.success(addr)
        logger.info(s"Coordinator bound to $addr")
      }
    }
  }

  override def stop(): Future[Unit] = {
    for {
      binding <- bindingP.future
      _ <- binding.terminate(10.seconds)
    } yield ()
  }
}
