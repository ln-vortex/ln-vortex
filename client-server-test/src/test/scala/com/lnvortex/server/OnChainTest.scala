package com.lnvortex.server

import akka.testkit.TestActorRef
import com.lnvortex.core.RoundDetails.getInitDetailsOpt
import com.lnvortex.core._
import com.lnvortex.testkit._
import org.bitcoins.core.number.UInt64
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.crypto.Sha256Digest
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.DurationInt

class OnChainTest
    extends ClientServerPairFixture
    with ClientServerTestUtils
    with EmbeddedPg {
  override val isNetworkingTest = false
  override val outputScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val changeScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val inputScriptType: ScriptType = WITNESS_V1_TAPROOT

  val testActor: TestActorRef[Nothing] = TestActorRef("OnChainTest-test")
  val peerId: Sha256Digest = Sha256Digest.empty

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
          assert(round.publicKey == coordinator.publicKey)
          assert(round.time == UInt64(roundDb.roundTime.getEpochSecond))
      }
    }
  }

  it must "get a nonce from the coordinator" in {
    case (client, coordinator, _) =>
      for {
        aliceDb <- getNonce(peerId, client, coordinator)

        aliceDbs <- coordinator.aliceDAO.findAll()
      } yield {
        assert(aliceDbs.size == 1)
        assert(aliceDb.nonce == aliceDbs.head.nonce)
      }
  }

  it must "register inputs" in { case (client, coordinator, _) =>
    for {
      _ <- registerInputs(peerId, client, coordinator)

      dbs <- coordinator.inputsDAO.findAll()
    } yield {
      val inputs = getInitDetailsOpt(client.getCurrentRoundDetails).get.inputs

      assert(dbs.size == inputs.size)
    }
  }

  it must "register inputs & outputs" in { case (client, coordinator, _) =>
    for {
      _ <- registerInputsAndOutputs(peerId, client, coordinator)
      outputDbs <- coordinator.outputsDAO.findAll()
    } yield {
      val expectedOutput =
        getInitDetailsOpt(client.getCurrentRoundDetails).get.mixOutput

      assert(outputDbs.size == 1)
      assert(outputDbs.head.output == expectedOutput)
    }
  }

  it must "sign the psbt" in { case (client, coordinator, _) =>
    for {
      signed <- signPSBT(peerId, client, coordinator)
    } yield assert(signed.inputMaps.exists(_.isFinalized))
  }

  it must "complete the round" in { case (client, coordinator, _) =>
    for {
      _ <- completeRound(peerId, client, coordinator)

      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      roundDbs <- coordinator.roundDAO.findAll()
    } yield assert(roundDbs.size == 2)
  }
}
