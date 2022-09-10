package com.lnvortex.develop

import akka.actor.ActorSystem
import com.lnvortex.client.VortexClientManager
import com.lnvortex.config.LnVortexRpcServerConfig
import com.lnvortex.coordinator
import com.lnvortex.coordinator.rpc.{LnVortexAppConfig => _, _}
import com.lnvortex.core.config.ServerArgParser
import com.lnvortex.lnd.LndVortexWallet
import com.lnvortex.rpc._
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import com.lnvortex.testkit.LndTestUtils
import grizzled.slf4j.Logging
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.util.NetworkUtil
import org.bitcoins.rpc.client.common.BitcoindVersion
import org.bitcoins.testkit.util.{BitcoindRpcTestClient, FileUtil}

import scala.concurrent.duration.DurationInt
import scala.concurrent._

object CreateLocalDevEnvironment extends App with Logging {

  val forceRegtest = Vector("--network", RegTest.toString)

  val port = NetworkUtil.randomPort()
  val listen = Vector("--listen", s"localhost:$port")

  val rpcUser = Vector("--rpcuser", "user")
  val rpcPassword = Vector("--rpcpassword", "password")

  val coordString =
    s"""[{name: "Dev Env", network: "$RegTest", onion: "localhost:$port"}]"""
  val coordConf = Vector("--coordinator", coordString)

  val serverArgParser = new ServerArgParser(
    args.toVector ++ forceRegtest ++ listen ++ coordConf ++ rpcUser ++ rpcPassword)

  val dataDir = serverArgParser.datadirOpt.getOrElse(
    coordinator.config.LnVortexRpcServerConfig.DEFAULT_DATADIR)

  System.setProperty("bitcoins.log.location", dataDir.toAbsolutePath.toString)

  implicit val system: ActorSystem = ActorSystem(
    s"vortex-dev-env-${FileUtil.randomDirName}")
  implicit val ec: ExecutionContext = system.dispatcher

  implicit val coordConfig: coordinator.rpc.LnVortexAppConfig =
    coordinator.rpc.LnVortexAppConfig
      .fromDatadir(dataDir, Vector(serverArgParser.toConfig("coordinator")))

  import coordConfig.coordinatorConfig

  val bitcoindTestClient =
    BitcoindRpcTestClient.fromSbtDownload(BitcoindVersion.newest)

  val startCoordinatorF = for {
    bitcoind <- bitcoindTestClient.start()
    height <- bitcoind.getBlockCount
    _ <- {
      if (height > 100) {
        logger.info(s"Bitcoind is already funded, we are at height=$height")
        Future.unit
      } else {
        logger.info(
          s"Bitcoind is not funded, we are at height=$height, funding now...")
        bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(101, _))
      }
    }
    (lnd, peerLnd) <- LndTestUtils.createNodePair(
      bitcoind,
      coordConfig.coordinatorConfig.inputScriptType)

    nodeId <- peerLnd.nodeId
    _ = logger.info(s"========================")
    _ = logger.info(s"Peer node id=$nodeId")
    _ = logger.info(s"========================")

    _ <- coordConfig.start()

    coordinator <- VortexCoordinator.initialize(bitcoind)

    httpServer = new VortexHttpServer(coordinator)
    _ <- httpServer.start()

    routes = VortexCoordinatorRpcRoutes(httpServer)
    server = CoordinatorRpcServer(
      handlers = Vector(routes),
      rpcBindOpt = coordConfig.rpcConfig.rpcBind,
      rpcPort = coordConfig.rpcConfig.rpcPort,
      rpcUser = coordConfig.rpcConfig.rpcUsername,
      rpcPassword = coordConfig.rpcConfig.rpcPassword
    )

    _ = logger.info("Starting coordinator rpc server")
    _ <- server.start()
    _ = logger.info("Vortex Coordinator started!")
  } yield (httpServer, lnd, peerLnd)

  startCoordinatorF.flatMap { case (httpServer, lnd, peerLnd) =>
    implicit val config: LnVortexAppConfig =
      LnVortexAppConfig.fromDatadir(dataDir,
                                    Vector(serverArgParser.toConfig("vortex")))

    import config.clientConfig

    implicit val serverConfig: LnVortexRpcServerConfig =
      config.rpcConfig

    val clientManager = new VortexClientManager(LndVortexWallet(lnd))

    logger.info("Starting client...")

    val configStartF = serverConfig.start()

    val server = RpcServer(
      handlers = Vector(LnVortexRoutes(clientManager)),
      rpcBindOpt = serverConfig.rpcBind,
      rpcPort = serverConfig.rpcPort,
      rpcUser = serverConfig.rpcUsername,
      rpcPassword = serverConfig.rpcPassword
    )

    val rpcStartF = configStartF.flatMap { _ =>
      logger.info("Starting client rpc server")
      server.start().map { _ =>
        logger.info(s"Vortex Client started on network ${config.network}!")
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
    } yield {
      sys.addShutdownHook {
        httpServer.currentCoordinator.stop()
        val stopFs = Vector(httpServer.stop(),
                            httpServer.currentCoordinator.bitcoind.stop(),
                            lnd.stop(),
                            peerLnd.stop())

        Await.result(Future.sequence(stopFs), 60.seconds)
        ()
      }
    }

    f.failed.foreach { ex =>
      ex.printStackTrace()
      logger.error("Error: ", ex)
    }

    f
  }
}
