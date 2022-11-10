package com.lnvortex.coordinator.rpc

import akka.actor.ActorSystem
import com.lnvortex.coordinator.config.LnVortexRpcServerConfig
import com.lnvortex.core.config.ServerArgParser
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import grizzled.slf4j.Logging
import org.bitcoins.core.config.{MainNet, RegTest, SigNet, TestNet3}

import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Random

object Daemon extends App with Logging {

  val serverArgParser = new ServerArgParser(args.toVector)

  System.setProperty(
    "bitcoins.log.location",
    LnVortexRpcServerConfig.DEFAULT_DATADIR.toAbsolutePath.toString)

  val randomStr = 0.until(5).map(_ => Random.alphanumeric.head).mkString

  implicit val system: ActorSystem = ActorSystem(
    s"ln-vortex-coordinator-${System.currentTimeMillis()}-$randomStr")
  implicit val ec: ExecutionContext = system.dispatcher

  implicit val config: CoordinatorRpcAppConfig =
    serverArgParser.datadirOpt match {
      case Some(datadir) =>
        CoordinatorRpcAppConfig.fromDatadir(
          datadir,
          Vector(
            serverArgParser.toConfig(VortexCoordinatorAppConfig.moduleName)))
      case None =>
        CoordinatorRpcAppConfig.fromDefaultDatadir(
          Vector(
            serverArgParser.toConfig(VortexCoordinatorAppConfig.moduleName)))
    }

  implicit val serverConfig: LnVortexRpcServerConfig =
    config.rpcConfig

  implicit val coordinatorConfig: VortexCoordinatorAppConfig =
    config.coordinatorConfig

  logger.info("Starting...")

  val f = for {
    _ <- config.start()

    // check bitcoind is working
    height <- config.bitcoind.getBlockCount
    goodHeight = config.network match {
      case MainNet  => height > 750_000
      case TestNet3 => height > 2_000_000
      case RegTest  => height >= 0
      case SigNet   => height >= 0
    }

    _ = if (!goodHeight)
      throw new RuntimeException("Failed to connect to bitcoind")

    coordinator <- VortexCoordinator.initialize(config.bitcoind)

    httpServer = new VortexHttpServer(coordinator)
    _ <- httpServer.start()

    routes = VortexCoordinatorRpcRoutes(httpServer)
    server = CoordinatorRpcServer(
      handlers = Vector(routes),
      rpcBindOpt = serverConfig.rpcBind,
      rpcPort = serverConfig.rpcPort,
      rpcUser = serverConfig.rpcUsername,
      rpcPassword = serverConfig.rpcPassword
    )

    _ = logger.info("Starting rpc server")
    _ <- server.start()
    _ = logger.info("Vortex Coordinator started!")
  } yield {
    sys.addShutdownHook {
      logger.info("Shutting down...")

      val f = for {
        _ <- httpServer.stop()
        _ = logger.info("Http server stopped")
        _ <- system.terminate()
      } yield logger.info("Shutdown complete")

      Await.result(f, 60.seconds)
    }
  }

  f.failed.foreach { ex =>
    ex.printStackTrace()
    logger.error("Error", ex)
  }
}
