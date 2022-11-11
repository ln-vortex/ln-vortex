package com.lnvortex.server.networking

import akka.actor._
import akka.http.scaladsl.Http
import com.lnvortex.core.VortexUtils
import com.lnvortex.core.api.CoordinatorAddress
import com.lnvortex.core.api.CoordinatorAddress._
import com.lnvortex.server.coordinator.VortexCoordinator
import grizzled.slf4j.Logging
import org.bitcoins.core.util.{NetworkUtil, StartStopAsync, TimeUtil}
import org.bitcoins.tor.TorController
import org.scalastr.core.NostrEvent
import play.api.libs.json.{JsArray, Json}

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

        broadcastAddrs = onionAddress
          .map(_.getHostName)
          .toVector ++ config.externalAddrs
        fs = broadcastAddrs.map { addr =>
          val fs = config.nostrClients.map { c =>
            val inet = NetworkUtil.parseInetSocketAddress(
              addr,
              VortexUtils.getDefaultPort(config.network))
            val coordAddr =
              CoordinatorAddress(config.coordinatorName, config.network, inet)

            val event =
              NostrEvent.build(
                privateKey = coordinator.km.privKey,
                created_at = TimeUtil.currentEpochSecond,
                kind = VortexUtils.NOSTR_KIND,
                tags = JsArray.empty,
                content = Json.toJson(coordAddr).toString
              )

            for {
              _ <- c.start()
              _ <- c.publishEvent(event)
            } yield ()
          }

          Future.sequence(fs).map(_ => ())
        }

        _ <- Future.sequence(fs).map(_ => ())
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
