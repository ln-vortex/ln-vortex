package com.lnvortex.server

import akka.http.scaladsl.model.ws.Message
import akka.stream._
import akka.stream.scaladsl._
import com.lnvortex.core.RoundDetails.getInitDetailsOpt
import com.lnvortex.server.config._
import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.crypto.Sha256Digest
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil
import scodec.bits.ByteVector

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
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

  it must "correctly reconcile a round" in {
    case (client, badClient, coordinator) =>
      val peerLnd = badClient.vortexWallet.lndRpcClient

      val completeCallback = new AtomicInteger(0)
      val onRoundComplete: OnRoundComplete = { _ =>
        completeCallback.incrementAndGet()
        Future.unit
      }

      val reconcileCallback = new AtomicInteger(0)
      val onRoundReconciled: OnRoundReconciled = { _ =>
        reconcileCallback.incrementAndGet()
        Future.unit
      }
      val callbacks = CoordinatorCallbacks(onRoundComplete, onRoundReconciled)
      coordinator.config.addCallbacks(callbacks)

      assert(coordinator.config.callBacks.onRoundComplete.nonEmpty)
      assert(coordinator.config.callBacks.onRoundReconciled.nonEmpty)

      for {
        all <- client.listCoins()
        _ <- registerInputsAndOutputs(peerIdA,
                                      peerIdB,
                                      client,
                                      badClient,
                                      coordinator)

        // registering inputs and outputs will make it construct the unsigned psbt
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.currentRound().map(_.psbtOpt.isDefined),
          interval = 100.milliseconds,
          maxTries = 500)
        psbt <- coordinator.currentRound().map(_.psbtOpt.get)

        signedA <- client.validateAndSignPsbt(psbt)
        idxA = signedA.inputMaps.indexWhere(_.isFinalized)
        idxB = signedA.inputMaps.indexWhere(!_.isFinalized)
        outPointA = signedA.transaction.inputs(idxA).previousOutput
        outPointB = signedA.transaction.inputs(idxB).previousOutput

        _ <- coordinator.registerPSBTSignatures(peerIdA, signedA)
        _ <- TestAsyncUtil.nonBlockingSleep(3.seconds)

        restartMsg <- coordinator.reconcileRound().map(_.head)
        newCoordinator <- coordinator.getNextCoordinator

        _ <- TestAsyncUtil.nonBlockingSleep(3.seconds)

        banned <- newCoordinator.bannedUtxoDAO.findAll()
        _ = assert(banned.size == 1)
        _ = assert(!banned.exists(_.outPoint == outPointA))
        _ = assert(banned.exists(_.outPoint == outPointB))

        _ <- client.restartRound(restartMsg)

        feeRate <- newCoordinator.currentRound().map(_.feeRate.get)
        // use fees from coordinator because we can't get ask inputs message here
        registerInputsA <- client.registerCoins(
          restartMsg.roundParams.roundId,
          newCoordinator.inputFee(feeRate),
          newCoordinator.outputFee(feeRate),
          newCoordinator.changeOutputFee(feeRate))
        blindSigA <- newCoordinator.registerAlice(peerIdA, registerInputsA)

        registerA = client.processBlindOutputSig(blindSigA)
        _ <- newCoordinator.beginOutputRegistration()

        _ <- newCoordinator.verifyAndRegisterBob(registerA)

        // registering inputs and outputs will make it construct the unsigned psbt
        _ <- TestAsyncUtil.awaitConditionF(
          () => newCoordinator.currentRound().map(_.psbtOpt.isDefined),
          interval = 100.milliseconds,
          maxTries = 50)
        psbt <- newCoordinator.currentRound().map(_.psbtOpt.get)

        signedA <- client.validateAndSignPsbt(psbt)

        _ <- newCoordinator.registerPSBTSignatures(peerIdA, signedA)
        tx <- newCoordinator.getCompletedTx
        _ <- client.completeRound(tx)

        _ = verifyFeeRate(feeRate, all, tx)

        // Mine some blocks
        _ <- newCoordinator.bitcoind.getNewAddress.flatMap(
          newCoordinator.bitcoind.generateToAddress(6, _))

        // wait until peerLnd sees new channel
        _ <- TestAsyncUtil.awaitConditionF(
          () => peerLnd.listChannels().map(_.nonEmpty),
          interval = 100.milliseconds,
          maxTries = 500)

        banned <- newCoordinator.bannedUtxoDAO.findAll()

        newCoinsA <- client.listCoins()
        newCoinsB <- badClient.listCoins()
      } yield {
        assert(completeCallback.get() == 1)
        assert(reconcileCallback.get() == 1)

        assert(banned.size == 1)
        assert(!banned.exists(_.outPoint == outPointA))
        assert(banned.exists(_.outPoint == outPointB))

        val changeA = newCoinsA.filter(_.isChange)
        assert(changeA.size == 1, s"${changeA.size} != 1")
        assert(changeA.forall(_.anonSet == 1))

        val changeB = newCoinsB.filter(_.isChange)
        assert(changeB.isEmpty)
      }
  }
}
