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
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait DualClientFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with LnVortexTestUtils
    with EmbeddedPg {
  def isNetworkingTest: Boolean

  override type FixtureParam = (
      VortexClient[LndVortexWallet],
      VortexClient[LndVortexWallet],
      VortexCoordinator)

  def outputScriptType: ScriptType
  def changeScriptType: ScriptType
  def inputScriptType: ScriptType

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(
        VortexClient[LndVortexWallet],
        VortexClient[LndVortexWallet],
        VortexCoordinator)](
      () => {
        val scriptTypeConfig =
          ConfigFactory
            .parseString(s"""
                            |coordinator.outputScriptType = $outputScriptType
                            |coordinator.changeScriptType = $changeScriptType
                            |coordinator.inputScriptType = $inputScriptType
                            |""".stripMargin)
        implicit val (_, serverConf) = getTestConfigs(Vector(scriptTypeConfig))

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator = VortexCoordinator(bitcoind)
          _ <- coordinator.start()
          (addr, _) <- coordinator.serverBindF

          _ = assert(serverConf.outputScriptType == outputScriptType)

          host =
            if (addr.getHostString == "0:0:0:0:0:0:0:0") "127.0.0.1"
            else addr.getHostString

          config = ConfigFactory.parseString(
            s"""vortex.coordinator = "$host:${addr.getPort}" """)
          clientConfigA = getTestConfigs(Vector(config))._1
          clientConfigB = getTestConfigs(Vector(config))._1
          _ <- clientConfigA.start()
          _ <- clientConfigB.start()

          (lndA, lndB) <- LndTestUtils.createNodePair(bitcoind, inputScriptType)
          clientA = VortexClient(LndVortexWallet(lndA))(system, clientConfigA)
          _ <- clientA.start()
          clientB = VortexClient(LndVortexWallet(lndB))(system, clientConfigB)
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

          _ <- coordinator.config.dropAll().map(_ => coordinator.config.clean())
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
