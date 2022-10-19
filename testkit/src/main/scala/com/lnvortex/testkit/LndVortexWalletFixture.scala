package com.lnvortex.testkit

import com.lnvortex.lnd.LndVortexWallet
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.core.number.UInt32
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestClient
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome
import lnrpc.AddressType

trait LndVortexWalletFixture extends BitcoinSFixture with CachedBitcoindV23 {

  override type FixtureParam = LndVortexWallet

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[LndVortexWallet](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF

          client = LndRpcTestClient.fromSbtDownload(Some(bitcoind))
          lnd <- client.start()

          addrA <- lnd.getNewAddress(AddressType.WITNESS_PUBKEY_HASH)
          addrB <- lnd.getNewAddress(AddressType.TAPROOT_PUBKEY)
          addrC <- lnd.getNewAddress(AddressType.TAPROOT_PUBKEY)

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
            lnd.getInfo.map(_.blockHeight == UInt32(height)))
          // Await funded
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.walletBalance().map(_.balance == Bitcoins(6)))

          // Await utxos
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.listUnspent.map(_.size == 3))

          wallet = LndVortexWallet(lnd)
          _ <- wallet.start()
        } yield LndVortexWallet(lnd)
      },
      { lnd =>
        for {
          _ <- lnd.stop()
        } yield ()
      }
    )(test)
  }
}
