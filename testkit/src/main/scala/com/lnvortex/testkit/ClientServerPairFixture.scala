package com.lnvortex.testkit

import com.lnvortex.client.VortexClient
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.testkit.LnVortexTestUtils.getTestConfigs
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait ClientServerPairFixture extends BitcoinSFixture with CachedBitcoindV21 {

  override type FixtureParam = (VortexClient, VortexCoordinator, LndRpcClient)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(VortexClient, VortexCoordinator, LndRpcClient)](
      () => {
        implicit val (clientConf, serverConf) = getTestConfigs()

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
