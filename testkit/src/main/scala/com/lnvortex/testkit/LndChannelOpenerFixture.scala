package com.lnvortex.testkit

import org.bitcoins.core.script.ScriptType
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.client.v23.BitcoindV23RpcClient
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

trait LndChannelOpenerFixture extends BitcoinSFixture with CachedBitcoindV23 {

  override type FixtureParam =
    (BitcoindV23RpcClient, LndRpcClient, LndRpcClient)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(BitcoindV23RpcClient, LndRpcClient, LndRpcClient)](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF
          (lndA, lndB) <- LndTestUtils.createNodePair(
            bitcoind,
            ScriptType.WITNESS_V0_KEYHASH)
        } yield (bitcoind, lndA, lndB)
      },
      { case (_, lndA, lndB) =>
        for {
          _ <- lndA.stop()
          _ <- lndB.stop()
        } yield ()
      }
    )(test)
  }
}
