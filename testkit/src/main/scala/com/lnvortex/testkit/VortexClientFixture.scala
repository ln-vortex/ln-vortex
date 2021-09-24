package com.lnvortex.testkit

import com.lnvortex.client.VortexClient
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.testkit.LnVortexTestUtils.getTestConfigs
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestClient
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

trait VortexClientFixture extends BitcoinSFixture with CachedBitcoindV21 {

  override type FixtureParam = VortexClient

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[VortexClient](
      () => {
        implicit val conf: VortexAppConfig = getTestConfigs()._1
        for {
          bitcoind <- cachedBitcoindWithFundsF

          client = LndRpcTestClient.fromSbtDownload(Some(bitcoind))
          lnd <- client.start()

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

        } yield VortexClient(lnd)
      },
      { vortex =>
        for {
          _ <- vortex.lndRpcClient.stop()
        } yield ()
      }
    )(test)
  }
}
