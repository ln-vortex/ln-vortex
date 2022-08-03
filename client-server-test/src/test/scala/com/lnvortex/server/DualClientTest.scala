package com.lnvortex.server

import akka.testkit.TestActorRef
import com.lnvortex.core.RoundDetails.getInitDetailsOpt
import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.crypto.Sha256Digest
import org.bitcoins.testkit.EmbeddedPg
import scodec.bits.ByteVector

class DualClientTest
    extends DualClientFixture
    with ClientServerTestUtils
    with EmbeddedPg {
  override val isNetworkingTest = false
  override val outputScriptType: ScriptType = WITNESS_V0_SCRIPTHASH
  override val inputScriptType: ScriptType = WITNESS_V0_KEYHASH
  override val changeScriptType: ScriptType = WITNESS_V0_KEYHASH

  override val testActor: TestActorRef[Nothing] = TestActorRef(
    "DualClientTest-test")
  val peerIdA: Sha256Digest = Sha256Digest(ByteVector.low(32))
  val peerIdB: Sha256Digest = Sha256Digest(ByteVector.high(32))

  it must "get nonces from the coordinator" in {
    case (clientA, clientB, coordinator) =>
      for {
        nonceA <- clientA.askNonce()
        nonceB <- clientB.askNonce()
        aliceDbs <- coordinator.aliceDAO.findAll()
      } yield {
        assert(aliceDbs.size == 2)
        assert(aliceDbs.exists(_.nonce == nonceA))
        assert(aliceDbs.exists(_.nonce == nonceB))
      }
  }

  it must "register inputs" in { case (clientA, clientB, coordinator) =>
    for {
      _ <- registerInputs(peerIdA, peerIdB, clientA, clientB, coordinator)

      dbs <- coordinator.inputsDAO.findAll()
    } yield {
      val inputsA = getInitDetailsOpt(clientA.getCurrentRoundDetails).get.inputs
      val inputsB = getInitDetailsOpt(clientB.getCurrentRoundDetails).get.inputs
      val inputs = inputsA ++ inputsB

      assert(dbs.size == inputs.size)
    }
  }

  it must "register inputs & outputs" in {
    case (clientA, clientB, coordinator) =>
      for {
        _ <- registerInputsAndOutputs(peerIdA,
                                      peerIdB,
                                      clientA,
                                      clientB,
                                      coordinator)

        outputDbs <- coordinator.outputsDAO.findAll()
      } yield {
        val expectedOutputA =
          getInitDetailsOpt(clientA.getCurrentRoundDetails).get.targetOutput
        val expectedOutputB =
          getInitDetailsOpt(clientB.getCurrentRoundDetails).get.targetOutput

        assert(outputDbs.size == 2)
        assert(outputDbs.exists(_.output == expectedOutputA))
        assert(outputDbs.exists(_.output == expectedOutputB))
      }
  }

  it must "sign the psbt" in { case (clientA, clientB, coordinator) =>
    for {
      (signedA, signedB) <- signPSBT(peerIdA,
                                     peerIdB,
                                     clientA,
                                     clientB,
                                     coordinator)
    } yield {
      assert(signedA.inputMaps.exists(_.isFinalized))
      assert(signedB.inputMaps.exists(_.isFinalized))
    }
  }

  it must "open channels" in { case (clientA, clientB, coordinator) =>
    for {
      _ <- completeChannelRound(peerIdA, peerIdB, clientA, clientB, coordinator)

      roundDbs <- coordinator.roundDAO.findAll()
    } yield assert(roundDbs.size == 2)
  }
}
