package com.lnvortex.testkit

import com.lnvortex.clightning.CLightningVortexWallet
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.clightning._
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.concurrent.duration.DurationInt

trait CLightningVortexWalletFixture
    extends BitcoinSFixture
    with CachedBitcoindV23 {

  override type FixtureParam = CLightningVortexWallet

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[CLightningVortexWallet](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF

          client = CLightningRpcTestClient.fromSbtDownload(Some(bitcoind))
          clightning <- client.start()
          // wait for rpc servers to start
          _ <- TestAsyncUtil.awaitCondition(
            () => clightning.instance.rpcFile.exists(),
            interval = 1.second,
            maxTries = 500)
          _ <- TestAsyncUtil.nonBlockingSleep(7.seconds)

          client = CLightningVortexWallet(clightning)

          addrA <- clightning.getNewAddress
          addrB <- clightning.getNewAddress
          addrC <- clightning.getNewAddress

          _ <- bitcoind.sendMany(
            Map(addrA -> Bitcoins(1),
                addrB -> Bitcoins(2),
                addrC -> Bitcoins(3)))
          _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))

          height <- bitcoind.getBlockCount

          // Await synced
          _ <- TestAsyncUtil.awaitConditionF(
            () => clightning.getInfo.map(_.blockheight == height),
            interval = 1.second,
            maxTries = 500)
          // Await funded
          _ <- TestAsyncUtil.awaitConditionF(
            () => clightning.walletBalance().map(_.balance == Bitcoins(6)),
            interval = 1.second,
            maxTries = 500)

          // Await utxos
          _ <- TestAsyncUtil.awaitConditionF(
            () => clightning.listFunds.map(_.outputs.nonEmpty),
            interval = 1.second,
            maxTries = 500)
        } yield client
      },
      { clightning =>
        for {
          _ <- TestAsyncUtil.nonBlockingSleep(3.seconds)
          _ <- clightning.clightning.stop()
        } yield ()
      }
    )(test)
  }
}
