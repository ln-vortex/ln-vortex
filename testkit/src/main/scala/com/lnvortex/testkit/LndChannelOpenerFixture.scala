package com.lnvortex.testkit

import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.client.v21.BitcoindV21RpcClient
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.bitcoins.testkit.util.FileUtil
import org.scalatest.FutureOutcome

import java.io.File
import java.nio.file.Path

trait LndChannelOpenerFixture extends BitcoinSFixture with CachedBitcoindV21 {

  def tmpDir(): Path = new File(
    s"/tmp/ln-vortex-test/${FileUtil.randomDirName}/").toPath

  override type FixtureParam =
    (BitcoindV21RpcClient, LndRpcClient, LndRpcClient)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(BitcoindV21RpcClient, LndRpcClient, LndRpcClient)](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF
          (lndA, lndB) <- LndTestUtils.createNodePair(bitcoind)
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
