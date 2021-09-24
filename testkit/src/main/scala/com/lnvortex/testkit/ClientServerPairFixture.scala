package com.lnvortex.testkit

import akka.actor.ActorSystem
import com.lnvortex.client.VortexClient
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.bitcoins.testkit.util.FileUtil
import org.scalatest.FutureOutcome

import java.io.File
import java.nio.file.Path
import scala.reflect.io.Directory

trait ClientServerPairFixture extends BitcoinSFixture with CachedBitcoindV21 {

  def tmpDir(): Path = new File(
    s"/tmp/ln-vortex-test/${FileUtil.randomDirName}/").toPath

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

  override type FixtureParam = (VortexClient, VortexCoordinator, LndRpcClient)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(VortexClient, VortexCoordinator, LndRpcClient)](
      () => {
        implicit val (clientConf, serverConf) = getTestConfig()

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator = VortexCoordinator(bitcoind)
          _ <- coordinator.start()

          (lnd, peerLnd) <- LndTestUtils.createNodePair(bitcoind)
          client = VortexClient(lnd)
          _ <- client.start()

          _ <- LndRpcTestUtil.connectLNNodes(lnd, peerLnd)

          // wait for it to receive mix advertisement
          _ <- TestAsyncUtil.awaitCondition(() => client.roundDetails.order > 0)
        } yield (client, coordinator, peerLnd)
      },
      { case (client, coordinator, peerLnd) =>
        for {
          _ <- peerLnd.stop()
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
