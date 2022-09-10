package com.lnvortex.rpc

import akka.actor.ActorSystem
import com.lnvortex.config.LnVortexRpcServerConfig
import com.lnvortex.core.config.ServerArgParser
import grizzled.slf4j.Logging

import scala.concurrent._
import scala.util.Random

object Daemon extends App with Logging {

  val serverArgParser = new ServerArgParser(args.toVector)

  System.setProperty(
    "bitcoins.log.location",
    LnVortexRpcServerConfig.DEFAULT_DATADIR.toAbsolutePath.toString)

  val randomStr = 0.until(5).map(_ => Random.alphanumeric.head).mkString

  implicit val system: ActorSystem = ActorSystem(
    s"ln-vortex-${System.currentTimeMillis()}-$randomStr")
  implicit val ec: ExecutionContext = system.dispatcher

  implicit val config: LnVortexAppConfig = serverArgParser.datadirOpt match {
    case Some(datadir) =>
      LnVortexAppConfig.fromDatadir(
        datadir,
        Vector(serverArgParser.toConfig(LnVortexAppConfig.moduleName)))
    case None =>
      LnVortexAppConfig.fromDefaultDatadir(
        Vector(serverArgParser.toConfig(LnVortexAppConfig.moduleName)))
  }

  implicit val serverConfig: LnVortexRpcServerConfig =
    config.rpcConfig

  val clientManager = config.clientManager

  logger.info("Starting...")

  val configStartF = serverConfig.start()

  val server = RpcServer(
    handlers = Vector(LnVortexRoutes(clientManager)),
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
      _ <- clientManager.config.start()
      _ <- clientManager.start()
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
