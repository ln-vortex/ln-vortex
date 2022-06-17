package com.lnvortex.testkit

import com.lnvortex.client.VortexClient
import com.lnvortex.lnd.LndVortexWallet
import com.lnvortex.server.coordinator.VortexCoordinator
import com.typesafe.config.ConfigFactory
import org.bitcoins.core.script.ScriptType
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait DualClientFixture
    extends BitcoinSFixture
    with CachedBitcoindV21
    with LnVortexTestUtils
    with EmbeddedPg {
  def isNetworkingTest: Boolean

  override type FixtureParam = (
      VortexClient[LndVortexWallet],
      VortexClient[LndVortexWallet],
      VortexCoordinator)

  def mixScriptType: ScriptType

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(
        VortexClient[LndVortexWallet],
        VortexClient[LndVortexWallet],
        VortexCoordinator)](
      () => {
        val scriptTypeConfig =
          ConfigFactory.parseString(s"vortex.mixScriptType = $mixScriptType")
        implicit val (_, serverConf) = getTestConfigs(Vector(scriptTypeConfig))

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator = VortexCoordinator(bitcoind)
          _ <- coordinator.start()
          (addr, _) <- coordinator.serverBindF

          host =
            if (addr.getHostString == "0:0:0:0:0:0:0:0") "127.0.0.1"
            else addr.getHostString

          config = ConfigFactory.parseString(
            s"""vortex.coordinator = "$host:${addr.getPort}" """)
          clientConfig = getTestConfigs(Vector(config))._1

          (lndA, lndB) <- LndTestUtils.createNodePair(bitcoind)
          clientA = VortexClient(LndVortexWallet(lndA))(system, clientConfig)
          _ <- clientA.start()
          clientB = VortexClient(LndVortexWallet(lndB))(system, clientConfig)
          _ <- clientB.start()

          _ <- LndRpcTestUtil.connectLNNodes(lndA, lndB)

          // wait for clients to receive mix details
          _ <- TestAsyncUtil.awaitCondition(() =>
            clientA.getCurrentRoundDetails.order > 0)
          _ <- TestAsyncUtil.awaitCondition(() =>
            clientB.getCurrentRoundDetails.order > 0)

          _ = if (!isNetworkingTest) coordinator.connectionHandlerMap.clear()
        } yield (clientA, clientB, coordinator)
      },
      { case (clientA, clientB, coordinator) =>
        for {
          _ <- clientA.vortexWallet.stop()
          _ <- clientA.stop()
          _ <- clientA.config.stop()

          _ <- clientB.vortexWallet.stop()
          _ <- clientB.stop()
          _ <- clientB.config.stop()

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
