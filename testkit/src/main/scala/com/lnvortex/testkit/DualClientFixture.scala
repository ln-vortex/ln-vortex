package com.lnvortex.testkit

import com.lnvortex.client.VortexClient
import com.lnvortex.client.config.CoordinatorAddress
import com.lnvortex.lnd.LndVortexWallet
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import com.typesafe.config.ConfigFactory
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.script.ScriptType
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.concurrent.Future
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
          coordinator <- VortexCoordinator.initialize(bitcoind)
          server = new VortexHttpServer(coordinator)
          _ <- server.start()
          addr <- server.bindingP.future.map(_.localAddress)

          _ = assert(serverConf.outputScriptType == outputScriptType)

          clientConfigA = getTestConfigs()._1
          clientConfigB = getTestConfigs()._1
          _ <- clientConfigA.start()
          _ <- clientConfigB.start()

          coordinatorAddr = CoordinatorAddress("test", RegTest, addr)

          (lndA, lndB) <- LndTestUtils.createNodePair(bitcoind, inputScriptType)
          clientA = VortexClient(LndVortexWallet(lndA), coordinatorAddr)(
            system,
            clientConfigA)
          _ <- clientA.start()
          clientB = VortexClient(LndVortexWallet(lndB), coordinatorAddr)(
            system,
            clientConfigB)
          _ <- clientB.start()

          _ <- LndRpcTestUtil.connectLNNodes(lndA, lndB)

          // wait for clients to receive round params
          _ <- TestAsyncUtil.awaitCondition(() =>
            clientA.getCurrentRoundDetails.order > 0)
          _ <- TestAsyncUtil.awaitCondition(() =>
            clientB.getCurrentRoundDetails.order > 0)

          _ <-
            if (!isNetworkingTest) {
              coordinator.connectionHandlerMap.clear()
              clientA.stop().flatMap(_ => clientB.stop())
            } else Future.unit
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
