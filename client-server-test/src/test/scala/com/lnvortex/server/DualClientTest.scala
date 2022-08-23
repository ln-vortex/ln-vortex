package com.lnvortex.server

import akka.http.scaladsl.model.ws.Message
import akka.stream._
import akka.stream.scaladsl._
import com.lnvortex.core.RoundDetails.getInitDetailsOpt
import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.crypto.Sha256Digest
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil
import scodec.bits.ByteVector

import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class DualClientTest
    extends DualClientFixture
    with ClientServerTestUtils
    with EmbeddedPg {
  override val isNetworkingTest = false
  override val outputScriptType: ScriptType = WITNESS_V0_SCRIPTHASH
  override val inputScriptType: ScriptType = WITNESS_V0_KEYHASH
  override val changeScriptType: ScriptType = WITNESS_V0_KEYHASH

  override def getDummyQueue: SourceQueueWithComplete[Message] = Source
    .queue[Message](bufferSize = 10,
                    OverflowStrategy.backpressure,
                    maxConcurrentOffers = 2)
    .toMat(BroadcastHub.sink)(Keep.left)
    .run()

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

      channelsA <- clientA.listChannels()
      channelsB <- clientB.listChannels()
      roundDbs <- coordinator.roundDAO.findAll()
    } yield {
      assert(roundDbs.size == 2)
      assert(channelsA.exists(_.anonSet == 2))
      assert(channelsB.exists(_.anonSet == 2))
    }
  }

  it must "correctly reconcile a round" ignore {
    case (clientA, clientB, coordinator) =>
      val peerLnd = clientB.vortexWallet.lndRpcClient

      for {
        all <- clientA.listCoins()
        _ <- registerInputsAndOutputs(peerIdA,
                                      peerIdB,
                                      clientA,
                                      clientB,
                                      coordinator)

        _ = println("registered inputs and outputs")

        // registering inputs and outputs will make it construct the unsigned psbt
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.currentRound().map(_.psbtOpt.isDefined),
          interval = 100.milliseconds,
          maxTries = 500)
        psbt <- coordinator.currentRound().map(_.psbtOpt.get)

        signedA <- clientA.validateAndSignPsbt(psbt)
        idxA = signedA.inputMaps.indexWhere(_.isFinalized)
        idxB = signedA.inputMaps.indexWhere(!_.isFinalized)
        outPointA = signedA.transaction.inputs(idxA).previousOutput
        outPointB = signedA.transaction.inputs(idxB).previousOutput

        registerF = coordinator.registerPSBTSignatures(peerIdA, signedA)
        _ <- TestAsyncUtil.nonBlockingSleep(3.seconds)

        _ = println("registered psbt signature")

        restartMsg <- coordinator.reconcileRound().map(_.head)
        newCoordinator <- coordinator.nextCoordinatorP.future

        _ <- recoverToSucceededIf[TimeoutException](registerF)
        _ <- TestAsyncUtil.nonBlockingSleep(3.seconds)

        banned <- newCoordinator.bannedUtxoDAO.findAll()
        _ = assert(banned.size == 1)
        _ = assert(!banned.exists(_.outPoint == outPointA))
        _ = assert(banned.exists(_.outPoint == outPointB))

        _ <- clientA.restartRound(restartMsg)

        // use fees from coordinator because we can't get ask inputs message here
        registerInputsA <- clientA.registerCoins(restartMsg.roundParams.roundId,
                                                 newCoordinator.inputFee,
                                                 newCoordinator.outputFee(),
                                                 newCoordinator.changeOutputFee)
        blindSigA <- newCoordinator.registerAlice(peerIdA, registerInputsA)

        registerA = clientA.processBlindOutputSig(blindSigA)
        _ <- newCoordinator.beginOutputRegistration()

        _ <- newCoordinator.verifyAndRegisterBob(registerA)

        // registering inputs and outputs will make it construct the unsigned psbt
        _ <- TestAsyncUtil.awaitConditionF(
          () => newCoordinator.currentRound().map(_.psbtOpt.isDefined),
          interval = 100.milliseconds,
          maxTries = 50)
        psbt <- newCoordinator.currentRound().map(_.psbtOpt.get)
        _ = println("got psbt")

        signedA <- clientA.validateAndSignPsbt(psbt)

        _ = newCoordinator.registerPSBTSignatures(peerIdA, signedA)
        tx <- newCoordinator.completedTxP.future
        _ <- clientA.completeRound(tx)

        inputUtxos = all.filter(t =>
          tx.inputs.map(_.previousOutput).contains(t.outPoint))
        inputAmt = inputUtxos.map(_.amount).sum
        feePaid = (inputAmt - tx.totalOutput).satoshis.toLong
        // regtest uses 1 sat/vbyte fee
        _ = assert(feePaid === tx.vsize +- 1, s"$feePaid != ${tx.vsize} +- 1")

        // Mine some blocks
        _ <- newCoordinator.bitcoind.getNewAddress.flatMap(
          newCoordinator.bitcoind.generateToAddress(6, _))

        // wait until peerLnd sees new channel
        _ <- TestAsyncUtil.awaitConditionF(
          () => peerLnd.listChannels().map(_.nonEmpty),
          interval = 100.milliseconds,
          maxTries = 500)
      } yield {
        succeed
      }
  }
}
