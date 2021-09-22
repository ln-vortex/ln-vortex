package com.lnvortex.server

import com.lnvortex.client._
import com.lnvortex.tests.ClientServerPairFixture
import org.bitcoins.core.number.UInt64
import org.bitcoins.testkit.async.TestAsyncUtil

class ClientServerPairTest extends ClientServerPairFixture {

  it must "get the correct round details" in { case (client, coordinator) =>
    coordinator.currentRound().map { roundDb =>
      client.roundDetails match {
        case NoDetails | _: ReceivedNonce | _: InitializedRound[_] =>
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

  it must "get a nonce from the coordinator" in { case (client, coordinator) =>
    for {
      nonce <- client.askNonce()
      aliceDbs <- coordinator.aliceDAO.findAll()
    } yield {
      assert(aliceDbs.size == 1)
      assert(nonce == aliceDbs.head.nonce)
    }
  }

  it must "register inputs" in { case (client, coordinator) =>
    for {
      _ <- client.askNonce()
      _ <- coordinator.beginInputRegistration()
      utxos <- client.listCoins()
      _ <- client.registerCoins(utxos.map(_.outPointOpt.get))
      // give time for messages to send
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO.findAll().map(_.size == utxos.size))
    } yield succeed
  }

  it must "register inputs & outputs" in { case (client, coordinator) =>
    for {
      _ <- client.askNonce()
      _ <- coordinator.beginInputRegistration()
      utxos <- client.listCoins()
      _ <- client.registerCoins(utxos.map(_.outPointOpt.get))
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO.findAll().map(_.size == utxos.size))
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
        maxTries = 500)
      outputDbs <- coordinator.outputsDAO.findAll()
    } yield {
      val expectedOutput =
        client.roundDetails
          .asInstanceOf[MixOutputRegistered]
          .initDetails
          .mixOutput
      assert(outputDbs.size == 1)
      assert(outputDbs.head.output == expectedOutput)
    }
  }
}
