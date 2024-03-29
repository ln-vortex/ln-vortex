package com.lnvortex.server

import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration._
import scala.util.Random

class OnChainNetworkingTest
    extends ClientServerPairFixture
    with EmbeddedPg
    with LnVortexTestUtils {
  override val isNetworkingTest = true
  override val outputScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val changeScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val inputScriptType: ScriptType = WITNESS_V1_TAPROOT

  val interval: FiniteDuration =
    if (torEnabled) 500.milliseconds else 100.milliseconds

  it must "cancel a registration and ask nonce again" in {
    case (client, _, _) =>
      for {
        _ <- client.askNonce()

        // don't select all coins
        utxos <- client.listCoins().map(_.take(1))
        addr <- client.vortexWallet.getNewAddress(outputScriptType)
        _ = client.queueCoins(utxos.map(_.outputReference), addr)

        _ <- client.cancelRegistration()
        _ <- client.askNonce()
      } yield succeed
  }

  it must "do a self spend" in { case (client, coordinator, _) =>
    for {
      _ <- client.askNonce()
      roundId = coordinator.getCurrentRoundId

      // don't select all coins
      all <- client.listCoins()
      utxos = Random.shuffle(all).take(1)
      addr <- client.vortexWallet.getNewAddress(
        coordinator.roundParams.outputType)
      _ = client.queueCoins(utxos.map(_.outputReference), addr)

      _ <- coordinator.beginInputRegistration()
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.inputsDAO.findAll().map(_.size == utxos.size),
        interval = interval,
        maxTries = 500)
      _ <- coordinator.beginOutputRegistration()
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
        interval = interval,
        maxTries = 500)
      // wait until we construct the unsigned tx
      // use getRound because we could start the new round
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

      // wait until we have new set of utxos
      // with our queued address
      _ <- TestAsyncUtil.awaitConditionF(
        () =>
          client.listCoins().map { utxos =>
            utxos != all && utxos.exists(_.address == addr)
          },
        interval = interval,
        maxTries = 500)

      roundDbs <- coordinator.roundDAO.findAll()
    } yield assert(roundDbs.size == 2)
  }
}
