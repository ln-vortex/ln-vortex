package com.lnvortex.server

import com.lnvortex.testkit.{DualClientFixture, LnVortexTestUtils}
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType.{
  WITNESS_V0_KEYHASH,
  WITNESS_V0_SCRIPTHASH
}
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

class DualClientNetworkingTest
    extends DualClientFixture
    with EmbeddedPg
    with LnVortexTestUtils {
  override val isNetworkingTest = true
  override val outputScriptType: ScriptType = WITNESS_V0_SCRIPTHASH
  override val inputScriptType: ScriptType = WITNESS_V0_KEYHASH
  override val changeScriptType: ScriptType = WITNESS_V0_KEYHASH

  val interval: FiniteDuration =
    if (torEnabled) 500.milliseconds else 100.milliseconds

  it must "open the channels" in { case (clientA, clientB, coordinator) =>
    for {
      nodeIdA <- clientA.vortexWallet.lndRpcClient.nodeId
      nodeIdB <- clientB.vortexWallet.lndRpcClient.nodeId
      roundId = coordinator.getCurrentRoundId

      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      // don't select all coins
      utxosA <- clientA.listCoins().map(c => Random.shuffle(c).take(1))
      _ <- clientA.queueCoins(utxosA.map(_.outputReference), nodeIdB, None)
      utxosB <- clientB.listCoins().map(c => Random.shuffle(c).take(1))
      _ <- clientB.queueCoins(utxosB.map(_.outputReference), nodeIdA, None)
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(
        () =>
          coordinator.inputsDAO
            .findAll()
            .map(_.size == utxosA.size + utxosB.size),
        interval = interval,
        maxTries = 500)
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.size == 2),
        interval = interval,
        maxTries = 500)
      // wait until we construct the unsigned tx
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        interval = interval,
        maxTries = 500)
      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        interval = interval,
        maxTries = 500)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until clientA sees new channels
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientA.listChannels().map(_.exists(_.anonSet == 2)),
        interval = interval,
        maxTries = 500)

      // wait until clientB sees new channels
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientB.listChannels().map(_.exists(_.anonSet == 2)),
        interval = interval,
        maxTries = 500)

      aliceDbs <- coordinator.aliceDAO.findByRoundId(roundId)
      outputDbs <- coordinator.outputsDAO.findByRoundId(roundId)
    } yield {
      assert(outputDbs.nonEmpty)
      val noNonceMatching = outputDbs.forall { db =>
        val nonce = db.sig.rx
        !aliceDbs.exists(_.nonce == nonce)
      }

      assert(noNonceMatching)
    }
  }
}
