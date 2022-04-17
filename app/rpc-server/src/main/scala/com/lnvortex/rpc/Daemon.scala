package com.lnvortex.rpc

import akka.actor.ActorSystem
import com.lnvortex.config.LnVortexRpcServerConfig
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

  val client = config.client

  logger.info("Starting...")

  val f = for {
    _ <- serverConfig.start()
    _ <- client.start()

    routes = LnVortexRoutes(client)
    server = RpcServer(
      handlers = Vector(routes),
      rpcBindOpt = serverConfig.rpcBind,
      rpcPort = serverConfig.rpcPort,
      rpcUser = serverConfig.rpcUsername,
      rpcPassword = serverConfig.rpcPassword
    )

    _ = logger.info("Starting rpc server")
    _ <- server.start()
    _ = logger.info(s"Ln Vortex Client started on network ${config.network}!")
  } yield ()

  f.failed.foreach { ex =>
    ex.printStackTrace()
    logger.error("Error", ex)
  }

}
