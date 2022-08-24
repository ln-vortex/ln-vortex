package com.lnvortex.testkit

import com.lnvortex.client.VortexClient
import com.lnvortex.client.config.CoordinatorAddress
import com.lnvortex.lnd.LndVortexWallet
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import com.typesafe.config.ConfigFactory
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.script.ScriptType
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait ClientServerPairFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with LnVortexTestUtils
    with EmbeddedPg {

  override type FixtureParam =
    (VortexClient[LndVortexWallet], VortexCoordinator, LndRpcClient)

  def isNetworkingTest: Boolean

  def outputScriptType: ScriptType
  def changeScriptType: ScriptType
  def inputScriptType: ScriptType

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(
        VortexClient[LndVortexWallet],
        VortexCoordinator,
        LndRpcClient)](
      () => {
        val scriptTypeConfig =
          ConfigFactory
            .parseString(s"""
                            |coordinator.outputScriptType = $outputScriptType
                            |coordinator.changeScriptType = $changeScriptType
                            |coordinator.inputScriptType = $inputScriptType
                            |""".stripMargin)
        implicit val (clientConfig, serverConf) =
          getTestConfigs(Vector(scriptTypeConfig))

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator <- VortexCoordinator.initialize(bitcoind)
          server = new VortexHttpServer(coordinator)
          _ <- server.start()
          addr <- server.getBinding.map(_.localAddress)

          _ = assert(serverConf.outputScriptType == outputScriptType)

          _ <- clientConfig.start()
          coordinatorAddr = CoordinatorAddress("test", RegTest, addr)

          (lnd, peerLnd) <- LndTestUtils.createNodePair(bitcoind,
                                                        inputScriptType)
          client = VortexClient(LndVortexWallet(lnd), coordinatorAddr)(
            system,
            clientConfig)
          _ <- client.start()

          _ <- LndRpcTestUtil.connectLNNodes(lnd, peerLnd)

          // wait for it to receive round params
          _ <- TestAsyncUtil.awaitCondition(() =>
            client.getCurrentRoundDetails.order > 0)

          // don't send message if not networking test
          _ = if (!isNetworkingTest) coordinator.connectionHandlerMap.clear()
        } yield (client, coordinator, peerLnd)
      },
      { case (client, coordinator, peerLnd) =>
        for {
          _ <- peerLnd.stop()
          _ <- client.vortexWallet.stop()
          _ <- client.stop()
          _ <- client.config.stop()

          _ <- coordinator.config.dropAll().map(_ => coordinator.config.clean())
          _ = coordinator.stop()
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
