package com.lnvortex.server

import com.lnvortex.client.RoundDetails.getInitDetailsOpt
import com.lnvortex.client._
import com.lnvortex.testkit.{ClientServerPairFixture, LnVortexTestUtils}
import org.bitcoins.core.number.UInt64
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ClientServerPairTest extends ClientServerPairFixture {

  val interval: FiniteDuration =
    if (LnVortexTestUtils.torEnabled) 500.milliseconds else 100.milliseconds

  it must "get the correct round details" in { case (client, coordinator, _) =>
    coordinator.currentRound().map { roundDb =>
      client.getCurrentRoundDetails match {
        case NoDetails | _: ReceivedNonce | _: InputsScheduled |
            _: InitializedRound =>
          fail("Invalid client round state")
        case KnownRound(round) =>
          assert(round.roundId == roundDb.roundId)
          assert(round.amount == roundDb.amount)
          assert(round.mixFee == roundDb.mixFee)
          assert(round.inputFee == roundDb.inputFee)
          assert(round.outputFee == roundDb.outputFee)
          assert(round.publicKey == coordinator.publicKey)
          assert(round.time == UInt64(roundDb.roundTime.getEpochSecond))
      }
    }
  }

  it must "get a nonce from the coordinator" in {
    case (client, coordinator, _) =>
      for {
        nonce <- client.askNonce()
        aliceDbs <- coordinator.aliceDAO.findAll()
      } yield {
        assert(aliceDbs.size == 1)
        assert(nonce == aliceDbs.head.nonce)
      }
  }

  it must "register inputs" in { case (client, coordinator, peerLnd) =>
    for {
      nodeId <- peerLnd.nodeId
      _ <- client.askNonce()
      // don't select all coins
      utxos <- client.listCoins.map(_.tail)
      _ = client.queueCoins(utxos.map(_.outputReference), nodeId, None)
      _ <- coordinator.beginInputRegistration()
      // give time for messages to send
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.inputsDAO.findAll().map(_.size == utxos.size),
        interval = interval,
        maxTries = 500)
    } yield succeed
  }

  it must "register inputs & outputs" in {
    case (client, coordinator, peerLnd) =>
      for {
        nodeId <- peerLnd.nodeId
        _ <- client.askNonce()
        // don't select all coins
        utxos <- client.listCoins.map(_.tail)
        _ = client.queueCoins(utxos.map(_.outputReference), nodeId, None)
        _ <- coordinator.beginInputRegistration()
        // wait until outputs are registered
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.inputsDAO.findAll().map(_.size == utxos.size),
          interval = interval,
          maxTries = 500)
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
          interval = interval,
          maxTries = 500)
        outputDbs <- coordinator.outputsDAO.findAll()
      } yield {
        val expectedOutput =
          getInitDetailsOpt(client.getCurrentRoundDetails).get.mixOutput

        assert(outputDbs.size == 1)
        assert(outputDbs.head.output == expectedOutput)
      }
  }

  it must "sign the psbt" in { case (client, coordinator, peerLnd) =>
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
    } yield succeed
  }

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
