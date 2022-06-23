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

  val configStartF = serverConfig.start()

  val server = RpcServer(
    handlers = Vector(LnVortexRoutes(client)),
    rpcBindOpt = serverConfig.rpcBind,
    rpcPort = serverConfig.rpcPort,
    rpcUser = serverConfig.rpcUsername,
    rpcPassword = serverConfig.rpcPassword
  )

  val rpcStartF = configStartF.flatMap { _ =>
    logger.info("Starting rpc server")
    server.start().map { _ =>
      logger.info(s"Ln Vortex Client started on network ${config.network}!")
    }
  }

  val clientF = configStartF.flatMap { _ =>
    for {
      _ <- client.config.start()
      _ <- client.start()
    } yield ()
  }

  val f = for {
    _ <- clientF
    _ <- rpcStartF
  } yield ()

  f.failed.foreach { ex =>
    ex.printStackTrace()
    logger.error("Error", ex)
  }
}
