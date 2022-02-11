package com.lnvortex.coordinator.rpc

import akka.actor.ActorSystem
import com.lnvortex.coordinator.config.LnVortexRpcServerConfig
import grizzled.slf4j.Logging

import scala.concurrent._

object Daemon extends App with Logging {

  System.setProperty(
    "bitcoins.log.location",
    LnVortexRpcServerConfig.DEFAULT_DATADIR.toAbsolutePath.toString)

  implicit val system: ActorSystem = ActorSystem("ln-vortex")
  implicit val ec: ExecutionContext = system.dispatcher

  implicit val config: LnVortexAppConfig =
    LnVortexAppConfig.fromDefaultDatadir()

  implicit val serverConfig: LnVortexRpcServerConfig =
    config.rpcConfig

  val coordinator = config.coordinator

  logger.info("Starting...")

  val f = for {
    _ <- config.start()
    _ <- serverConfig.start()
    _ <- coordinator.start()

    routes = LnVortexRoutes(coordinator)
    server = RpcServer(
      handlers = Vector(routes),
      rpcBindOpt = serverConfig.rpcBind,
      rpcPort = serverConfig.rpcPort,
      rpcUser = serverConfig.rpcUsername,
      rpcPassword = serverConfig.rpcPassword
    )

    _ = logger.info("Starting rpc server")
    _ <- server.start()
    _ = logger.info("Ln Vortex Coordinator started!")
  } yield ()

  f.failed.foreach { ex =>
    ex.printStackTrace()
    logger.error("Error", ex)
  }
}
