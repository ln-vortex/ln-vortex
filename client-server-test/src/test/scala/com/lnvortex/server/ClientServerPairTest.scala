package com.lnvortex.server

import com.lnvortex.client.RoundDetails.getInitDetailsOpt
import com.lnvortex.client._
import com.lnvortex.testkit.ClientServerPairFixture
import org.bitcoins.core.number.UInt64
import org.bitcoins.testkit.async.TestAsyncUtil

class ClientServerPairTest extends ClientServerPairFixture {

  it must "get the correct round details" in { case (client, coordinator, _) =>
    coordinator.currentRound().map { roundDb =>
      client.getCurrentRoundDetails match {
        case NoDetails | _: ReceivedNonce | _: InitializedRound =>
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
      _ <- coordinator.beginInputRegistration()
      // don't select all coins
      utxos <- client.listCoins().map(_.tail)
      _ <- client.registerCoins(utxos.map(_.outPointOpt.get), nodeId, None)
      // give time for messages to send
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO.findAll().map(_.size == utxos.size))
    } yield succeed
  }

  it must "register inputs & outputs" in {
    case (client, coordinator, peerLnd) =>
      for {
        nodeId <- peerLnd.nodeId
        _ <- client.askNonce()
        _ <- coordinator.beginInputRegistration()
        // don't select all coins
        utxos <- client.listCoins().map(_.tail)
        _ <- client.registerCoins(utxos.map(_.outPointOpt.get), nodeId, None)
        // wait until outputs are registered
        _ <- TestAsyncUtil.awaitConditionF(() =>
          coordinator.inputsDAO.findAll().map(_.size == utxos.size))
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
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

      _ <- coordinator.beginInputRegistration()
      // don't select all coins
      utxos <- client.listCoins().map(_.tail)
      _ <- client.registerCoins(utxos.map(_.outPointOpt.get), nodeId, None)
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO.findAll().map(_.size == utxos.size))
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
        maxTries = 500)
      // wait until we construct the unsigned tx
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        maxTries = 500)

      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        maxTries = 500)
    } yield succeed
  }

  it must "open a channel" in { case (client, coordinator, peerLnd) =>
    for {
      nodeId <- peerLnd.nodeId
      _ <- client.askNonce()
      roundId = coordinator.getCurrentRoundId

      _ <- coordinator.beginInputRegistration()
      // don't select all coins
      utxos <- client.listCoins().map(_.tail)
      _ <- client.registerCoins(utxos.map(_.outPointOpt.get), nodeId, None)
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO.findAll().map(_.size == utxos.size))
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
        maxTries = 500)
      // wait until we construct the unsigned tx
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        maxTries = 500)
      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        maxTries = 500)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until peerLnd sees new channel
      _ <- TestAsyncUtil.awaitConditionF(
        () => peerLnd.listChannels().map(_.nonEmpty),
        maxTries = 500)

      roundDbs <- coordinator.roundDAO.findAll()
    } yield assert(roundDbs.size == 2)
  }
}
