package com.lnvortex.server.networking

import akka.actor._
import akka.http.scaladsl.Http
import com.lnvortex.server.coordinator.VortexCoordinator
import grizzled.slf4j.Logging
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.tor.TorController

import scala.concurrent._

class VortexHttpServer(coordinator: VortexCoordinator)(implicit
    val system: ActorSystem)
    extends StartStopAsync[Unit]
    with Logging {
  implicit val executionContext: ExecutionContext = system.dispatcher

  private val bindingP: Promise[Http.ServerBinding] =
    Promise[Http.ServerBinding]()

  def getBinding: Future[Http.ServerBinding] = bindingP.future

  override def start(): Future[Unit] = {
    if (bindingP.isCompleted) {
      Future.successful(())
    } else {
      val bindAddress = coordinator.config.listenAddress
      for {
        onionAddress <- coordinator.config.torParams match {
          case Some(params) =>
            TorController
              .setUpHiddenService(
                params.controlAddress,
                params.authentication,
                params.privateKeyPath,
                bindAddress.getPort
              )
              .map(Some(_))
          case None => Future.successful(None)
        }
        routes = new CoordinatorRoutes(coordinator).topLevelRoute
        bind <- Http()
          .newServerAt(bindAddress.getHostName, bindAddress.getPort)
          .bind(routes)
      } yield {
        val addr = onionAddress.getOrElse(bind.localAddress)
        bindingP.success(bind)
        logger.info(s"Coordinator bound to $addr")
      }
    }
  }

  override def stop(): Future[Unit] = {
    for {
      binding <- bindingP.future
      _ <- binding.unbind()
    } yield ()
  }
}
