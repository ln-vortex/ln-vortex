package com.lnvortex.coordinator.rpc

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import ujson._

import scala.concurrent._

case class VortexCoordinatorRpcRoutes(server: VortexHttpServer)(implicit
    system: ActorSystem)
    extends ServerRoute {
  implicit val ec: ExecutionContext = system.dispatcher

  private def coordinator: VortexCoordinator = server.currentCoordinator

  override def handleCommand: PartialFunction[ServerCommand, Route] = {
    case ServerCommand(_, "getinfo", _) =>
      complete {
        for {
          addr <- server.getHostAddress
        } yield {
          val obj = Obj(
            ("publicKey", Str(coordinator.publicKey.hex)),
            ("onion", Str(addr.toString)),
            ("roundId", Str(coordinator.getCurrentRoundId.hex))
          )
          CoordinatorRpcServer.httpSuccess(obj)
        }
      }
  }
}
