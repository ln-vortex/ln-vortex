package com.lnvortex.testkit

import com.lnvortex.client.VortexClient
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.testkit.LnVortexTestUtils.getTestConfigs
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait DualClientFixture extends BitcoinSFixture with CachedBitcoindV21 {

  override type FixtureParam = (VortexClient, VortexClient, VortexCoordinator)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(VortexClient, VortexClient, VortexCoordinator)](
      () => {
        implicit val (clientConf, serverConf) = getTestConfigs()

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator = VortexCoordinator(bitcoind)
          _ <- coordinator.start()

          (lndA, lndB) <- LndTestUtils.createNodePair(bitcoind)
          clientA = VortexClient(lndA)
          _ <- clientA.start()
          clientB = VortexClient(lndB)
          _ <- clientB.start()

          _ <- LndRpcTestUtil.connectLNNodes(lndA, lndB)

          // wait for clients to receive mix details
          _ <- TestAsyncUtil.awaitCondition(() =>
            clientA.getCurrentRoundDetails.order > 0)
          _ <- TestAsyncUtil.awaitCondition(() =>
            clientB.getCurrentRoundDetails.order > 0)
        } yield (clientA, clientB, coordinator)
      },
      { case (clientA, clientB, coordinator) =>
        for {
          _ <- clientA.lndRpcClient.stop()
          _ <- clientA.stop()
          _ <- clientA.config.stop()

          _ <- clientB.lndRpcClient.stop()
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
