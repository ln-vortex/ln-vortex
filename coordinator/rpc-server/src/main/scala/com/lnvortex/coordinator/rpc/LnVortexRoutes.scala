package com.lnvortex.coordinator.rpc

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.lnvortex.server.coordinator.VortexCoordinator
import ujson.Null

import scala.concurrent._

case class LnVortexRoutes(coordinator: VortexCoordinator)(implicit
    system: ActorSystem)
    extends ServerRoute {
  implicit val ec: ExecutionContext = system.dispatcher

  override def handleCommand: PartialFunction[ServerCommand, Route] = {
    case ServerCommand("listutxos", _) =>
      complete {
        RpcServer.httpSuccess(Null)
      }
  }
}
