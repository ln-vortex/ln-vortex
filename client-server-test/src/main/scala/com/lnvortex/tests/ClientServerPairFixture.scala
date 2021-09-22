package com.lnvortex.tests

import akka.actor.ActorSystem
import com.lnvortex.client.VortexClient
import com.lnvortex.core.VortexAppConfig
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.core.currency._
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestClient
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest._

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.reflect.io.Directory

trait ClientServerPairFixture extends BitcoinSFixture with CachedBitcoindV21 {

  def tmpDir(): Path = Files.createTempDirectory("ln-vortex-")

  def getTestConfig(config: Config*)(implicit
      system: ActorSystem): (VortexAppConfig, VortexCoordinatorAppConfig) = {
    val listenPort = RpcUtil.randomPort
    val dir = tmpDir()
    // todo fix tor
    val overrideConf = ConfigFactory.parseString {
      s"""
         |bitcoin-s {
         |  proxy.enabled = false
         |  tor.enabled = false
         |  tor.use-random-ports = false
         |}
         |vortex {
         |  listen = "0.0.0.0:$listenPort"
         |  coordinator = "127.0.0.1:$listenPort"
         |}
      """.stripMargin
    }

    val clientConf = VortexAppConfig(dir, overrideConf +: config: _*)
    val serverConf = VortexCoordinatorAppConfig(dir, overrideConf +: config: _*)

    (clientConf, serverConf)
  }

  override type FixtureParam = (VortexClient, VortexCoordinator)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(VortexClient, VortexCoordinator)](
      () => {
        implicit val (clientConf, serverConf) = getTestConfig()
        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator = VortexCoordinator(bitcoind)
          _ <- coordinator.start()

          lndClient = LndRpcTestClient.fromSbtDownload(Some(bitcoind))
          _ <- clientConf.start()
          lnd <- lndClient.start()

          addrA <- lnd.getNewAddress
          addrB <- lnd.getNewAddress
          addrC <- lnd.getNewAddress

          _ <- bitcoind.sendMany(
            Map(addrA -> Bitcoins(1),
                addrB -> Bitcoins(2),
                addrC -> Bitcoins(3)))
          _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))

          height <- bitcoind.getBlockCount

          // Await synced
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.getInfo.map(_.syncedToChain))
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.getInfo.map(_.blockHeight == height))
          // Await funded
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.walletBalance().map(_.balance == Bitcoins(6)))

          // Await utxos
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.listUnspent.map(_.nonEmpty))

          client = VortexClient(lnd)
          _ <- client.start()
          _ <- TestAsyncUtil.nonBlockingSleep(100.milliseconds)
        } yield (client, coordinator)
      },
      { case (client, coordinator) =>
        for {
          _ <- client.lndRpcClient.stop()
          _ <- client.stop()
          _ <- client.config.stop()

          _ <- coordinator.stop()
          _ <- coordinator.config.stop()
        } yield {
          val directory = new Directory(coordinator.config.baseDatadir.toFile)
          directory.deleteRecursively()
          ()
        }
      }
    )(test)
  }
}
