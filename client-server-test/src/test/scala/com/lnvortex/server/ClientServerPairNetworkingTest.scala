package com.lnvortex.server

import com.lnvortex.testkit.{ClientServerPairFixture, LnVortexTestUtils}
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ClientServerPairNetworkingTest
    extends ClientServerPairFixture
    with EmbeddedPg {
  override val isNetworkingTest = true

  val interval: FiniteDuration =
    if (LnVortexTestUtils.torEnabled) 500.milliseconds else 100.milliseconds

  it must "open a channel" in { case (client, coordinator, peerLnd) =>
    for {
      nodeId <- peerLnd.nodeId
      _ <- client.askNonce()
      roundId = coordinator.getCurrentRoundId

      // don't select all coins
      utxos <- client.listCoins.map(_.tail)
      _ = client.queueCoins(utxos.map(_.outputReference), nodeId, None)

      _ <- coordinator.beginInputRegistration()
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.inputsDAO.findAll().map(_.size == utxos.size),
        interval = interval,
        maxTries = 500)
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

      // wait until peerLnd sees new channel
      _ <- TestAsyncUtil.awaitConditionF(
        () => peerLnd.listChannels().map(_.nonEmpty),
        interval = interval,
        maxTries = 500)

      roundDbs <- coordinator.roundDAO.findAll()
    } yield assert(roundDbs.size == 2)
  }
}
