package com.lnvortex.testkit

import com.lnvortex.client.lnd.LndCoinJoinWallet
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestClient
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

trait LndCoinJoinWalletFixture extends BitcoinSFixture with CachedBitcoindV21 {

  override type FixtureParam = LndCoinJoinWallet

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[LndCoinJoinWallet](
      () => {
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

        } yield LndCoinJoinWallet(lnd)
      },
      { lnd =>
        for {
          _ <- lnd.stop()
        } yield ()
      }
    )(test)
  }
}
