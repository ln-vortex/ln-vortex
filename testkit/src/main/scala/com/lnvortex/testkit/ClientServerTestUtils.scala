package com.lnvortex.testkit

import akka.testkit.TestActorRef
import com.lnvortex.client._
import com.lnvortex.core.AskNonce
import com.lnvortex.core.RoundDetails.getMixDetailsOpt
import com.lnvortex.lnd.LndVortexWallet
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.models.AliceDb
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.Int32
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.{DoubleSha256DigestBE, FieldElement, Sha256Digest}
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.Assertions.convertToEqualizer

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait ClientServerTestUtils {

  def testActor: TestActorRef[Nothing]

  def getNonce(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[AliceDb] = {
    for {
      aliceDb <- coordinator.getNonce(peerId,
                                      testActor,
                                      AskNonce(coordinator.getCurrentRoundId))
      _ = client.storeNonce(aliceDb.nonce)
    } yield aliceDb
  }

  def registerInputs(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator,
      peerLnd: LndRpcClient)(implicit
      ec: ExecutionContext): Future[FieldElement] = {
    for {
      nodeId <- peerLnd.nodeId
      _ <- getNonce(peerId, client, coordinator)
      // select random minimal utxo
      utxos <- client.listCoins().map(c => Random.shuffle(c).take(1))
      _ <- client.queueCoins(utxos.map(_.outputReference), nodeId, None)
      msg <- coordinator.beginInputRegistration()

      registerInputs <- client.registerCoins(msg.roundId,
                                             msg.inputFee,
                                             msg.outputFee,
                                             msg.changeOutputFee)
      blindSig <- coordinator.registerAlice(peerId, registerInputs)
    } yield blindSig
  }

  def registerInputs(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[FieldElement] = {
    for {
      _ <- getNonce(peerId, client, coordinator)
      // select random minimal utxo
      utxos <- client.listCoins().map(c => Random.shuffle(c).take(1))
      addr <- client.vortexWallet.getNewAddress(
        coordinator.mixDetails.outputType)
      _ = client.queueCoins(utxos.map(_.outputReference), addr)
      msg <- coordinator.beginInputRegistration()

      registerInputs <- client.registerCoins(msg.roundId,
                                             msg.inputFee,
                                             msg.outputFee,
                                             msg.changeOutputFee)
      blindSig <- coordinator.registerAlice(peerId, registerInputs)
    } yield blindSig
  }

  def registerInputs(
      peerIdA: Sha256Digest,
      peerIdB: Sha256Digest,
      clientA: VortexClient[LndVortexWallet],
      clientB: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[(FieldElement, FieldElement)] = {
    for {
      nodeIdA <- clientA.vortexWallet.lndRpcClient.nodeId
      nodeIdB <- clientB.vortexWallet.lndRpcClient.nodeId
      _ <- getNonce(peerIdA, clientA, coordinator)
      _ <- getNonce(peerIdB, clientB, coordinator)

      // select random minimal utxo
      utxos <- clientA.listCoins().map(c => Random.shuffle(c).take(1))
      _ <- clientA.queueCoins(utxos.map(_.outputReference), nodeIdB, None)

      // select random minimal utxo
      utxos <- clientB.listCoins().map(c => Random.shuffle(c).take(1))
      _ <- clientB.queueCoins(utxos.map(_.outputReference), nodeIdA, None)

      msg <- coordinator.beginInputRegistration()

      registerInputsA <- clientA.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee,
                                               msg.changeOutputFee)
      blindSigA <- coordinator.registerAlice(peerIdA, registerInputsA)

      registerInputsB <- clientB.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee,
                                               msg.changeOutputFee)
      blindSigB <- coordinator.registerAlice(peerIdB, registerInputsB)
    } yield (blindSigA, blindSigB)
  }

  def registerInputsAndOutputs(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator,
      peerLnd: LndRpcClient)(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      blindSig <- registerInputs(peerId, client, coordinator, peerLnd)

      registerMixOutput = client.processBlindOutputSig(blindSig)

      _ <- coordinator.beginOutputRegistration()

      _ <- coordinator.verifyAndRegisterBob(registerMixOutput)
    } yield ()
  }

  def registerInputsAndOutputs(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[Unit] = {
    for {
      blindSig <- registerInputs(peerId, client, coordinator)

      registerMixOutput = client.processBlindOutputSig(blindSig)

      _ <- coordinator.beginOutputRegistration()

      _ <- coordinator.verifyAndRegisterBob(registerMixOutput)
    } yield ()
  }

  def registerInputsAndOutputs(
      peerIdA: Sha256Digest,
      peerIdB: Sha256Digest,
      clientA: VortexClient[LndVortexWallet],
      clientB: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[Unit] = {
    for {
      (blindSigA, blindSigB) <- registerInputs(peerIdA,
                                               peerIdB,
                                               clientA,
                                               clientB,
                                               coordinator)

      registerMixOutputA = clientA.processBlindOutputSig(blindSigA)
      registerMixOutputB = clientB.processBlindOutputSig(blindSigB)

      _ <- coordinator.beginOutputRegistration()

      _ <- coordinator.verifyAndRegisterBob(registerMixOutputA)
      _ <- coordinator.verifyAndRegisterBob(registerMixOutputB)
    } yield ()
  }

  def signPSBT(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator,
      peerLnd: LndRpcClient)(implicit ec: ExecutionContext): Future[PSBT] = {
    for {
      _ <- registerInputsAndOutputs(peerId, client, coordinator, peerLnd)

      // registering inputs and outputs will make it construct the unsigned psbt
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.currentRound().map(_.psbtOpt.isDefined),
        interval = 100.milliseconds,
        maxTries = 500)
      psbt <- coordinator.currentRound().map(_.psbtOpt.get)

      signed <- client.validateAndSignPsbt(psbt)
    } yield signed
  }

  def signPSBT(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[PSBT] = {
    for {
      _ <- registerInputsAndOutputs(peerId, client, coordinator)

      // registering inputs and outputs will make it construct the unsigned psbt
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.currentRound().map(_.psbtOpt.isDefined),
        interval = 100.milliseconds,
        maxTries = 500)
      psbt <- coordinator.currentRound().map(_.psbtOpt.get)

      signed <- client.validateAndSignPsbt(psbt)
    } yield signed
  }

  def signPSBT(
      peerIdA: Sha256Digest,
      peerIdB: Sha256Digest,
      clientA: VortexClient[LndVortexWallet],
      clientB: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[(PSBT, PSBT)] = {
    for {
      _ <- registerInputsAndOutputs(peerIdA,
                                    peerIdB,
                                    clientA,
                                    clientB,
                                    coordinator)

      // registering inputs and outputs will make it construct the unsigned psbt
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.currentRound().map(_.psbtOpt.isDefined),
        interval = 100.milliseconds,
        maxTries = 500)
      psbt <- coordinator.currentRound().map(_.psbtOpt.get)

      signedA <- clientA.validateAndSignPsbt(psbt)
      signedB <- clientB.validateAndSignPsbt(psbt)
    } yield (signedA, signedB)
  }

  def completeMixRound(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator,
      peerLnd: LndRpcClient)(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      all <- client.listCoins()
      psbt <- signPSBT(peerId, client, coordinator, peerLnd)

      tx <- coordinator.registerPSBTSignatures(peerId, psbt)

      _ <- client.completeRound(tx)

      inputUtxos = all.filter(t =>
        tx.inputs.map(_.previousOutput).contains(t.outPoint))
      inputAmt = inputUtxos.map(_.amount).sum
      // regtest uses 1 sat/vbyte fee
      _ = assert(SatoshisPerVirtualByte.calc(inputAmt, tx).toLong == 1)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until peerLnd sees new channel
      _ <- TestAsyncUtil.awaitConditionF(
        () => peerLnd.listChannels().map(_.nonEmpty),
        interval = 100.milliseconds,
        maxTries = 500)
    } yield ()
  }

  def completeRound(
      peerId: Sha256Digest,
      client: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[Unit] = {
    for {
      all <- client.listCoins()
      psbt <- signPSBT(peerId, client, coordinator)

      tx <- coordinator.registerPSBTSignatures(peerId, psbt)

      _ <- client.completeRound(tx)

      inputUtxos = all.filter(t =>
        tx.inputs.map(_.previousOutput).contains(t.outPoint))
      inputAmt = inputUtxos.map(_.amount).sum
      feePaid = (inputAmt - tx.totalOutput).satoshis.toLong
      // regtest uses 1 sat/vbyte fee
      _ = assert(feePaid === tx.vsize +- 2, s"$feePaid != ${tx.vsize} +- 2")

      _ <- coordinator.bitcoind.sendRawTransaction(tx)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until new set of utxos
      _ <- TestAsyncUtil.awaitConditionF(() => client.listCoins().map(_ != all),
                                         interval = 100.milliseconds,
                                         maxTries = 500)
      newCoins <- client.listCoins()
    } yield {
      val change = newCoins.filter(_.isChange)
      assert(change.size == 1)
      assert(change.forall(_.anonSet == 1))
    }
  }

  def completeMixRound(
      peerIdA: Sha256Digest,
      peerIdB: Sha256Digest,
      clientA: VortexClient[LndVortexWallet],
      clientB: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[Unit] = {
    for {
      utxosA <- clientA.listCoins()
      utxosB <- clientB.listCoins()

      (psbtA, psbtB) <- signPSBT(peerIdA,
                                 peerIdB,
                                 clientA,
                                 clientB,
                                 coordinator)

      // do async so they can complete
      regAF = coordinator.registerPSBTSignatures(peerIdA, psbtA)
      regBF = coordinator.registerPSBTSignatures(peerIdB, psbtB)

      tx <- regAF
      tx2 <- regBF

      _ = require(tx == tx2)

      _ <- clientA.completeRound(tx)
      _ <- clientB.completeRound(tx)

      inputUtxos = (utxosA ++ utxosB).filter(t =>
        tx.inputs.map(_.previousOutput).contains(t.outPoint))
      inputAmt = inputUtxos.map(_.amount).sum
      feePaid = (inputAmt - tx.totalOutput).satoshis.toLong
      // regtest uses 1 sat/vbyte fee
      _ = assert(feePaid === tx.vsize +- 1, s"$feePaid != ${tx.vsize} +- 1")

      _ <- coordinator.bitcoind.sendRawTransaction(tx)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until clientA sees new channel
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientA.vortexWallet.lndRpcClient.listChannels().map(_.nonEmpty),
        interval = 100.milliseconds,
        maxTries = 500)

      // wait until clientB sees new channel
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientB.vortexWallet.lndRpcClient.listChannels().map(_.nonEmpty),
        interval = 100.milliseconds,
        maxTries = 500)

      newCoinsA <- clientA.listCoins()
      newCoinsB <- clientB.listCoins()
    } yield {
      val changeA = newCoinsA.filter(_.isChange)
      assert(changeA.size == 1, s"${changeA.size} != 1")
      assert(changeA.forall(_.anonSet == 1))

      val changeB = newCoinsB.filter(_.isChange)
      assert(changeB.size == 1, s"${changeB.size} != 1")
      assert(changeB.forall(_.anonSet == 1))
    }
  }

  def completeOnChainRound(
      txidOpt: Option[DoubleSha256DigestBE],
      peerIdA: Sha256Digest,
      peerIdB: Sha256Digest,
      clientA: VortexClient[LndVortexWallet],
      clientB: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[Transaction] = {
    // verify same roundId
    require(coordinator.getCurrentRoundId == getMixDetailsOpt(
              clientA.getCurrentRoundDetails).get.roundId,
            "Incorrect round id")
    require(coordinator.getCurrentRoundId == getMixDetailsOpt(
              clientB.getCurrentRoundDetails).get.roundId,
            "Incorrect round id")

    val roundAmount = coordinator.mixDetails.amount

    for {
      _ <- getNonce(peerIdA, clientA, coordinator)
      _ <- getNonce(peerIdB, clientB, coordinator)
      // select remix coins
      utxosA <- clientA.listCoins().map { coins =>
        txidOpt match {
          case Some(txid) =>
            coins
              .filter(_.outPoint.txIdBE == txid)
              .filter(_.amount == coordinator.config.mixAmount)
          case None => Random.shuffle(coins).take(1)
        }
      }
      addrA <- clientA.vortexWallet.getNewAddress(
        coordinator.mixDetails.outputType)
      coinsA = Random.shuffle(utxosA.map(_.outputReference)).take(1)
      _ = clientA.queueCoins(coinsA, addrA)
      // select non-remix coins
      utxosB <- clientB.listCoins().map { coins =>
        txidOpt match {
          case Some(txid) =>
            coins.filterNot(_.outPoint.txIdBE == txid)
          case None => Random.shuffle(coins).take(1)
        }
      }
      addrB <- clientB.vortexWallet.getNewAddress(
        coordinator.mixDetails.outputType)
      coinsB = Random.shuffle(utxosB.map(_.outputReference)).take(1)
      _ = clientB.queueCoins(coinsB, addrB)
      msg <- coordinator.beginInputRegistration()

      registerInputsA <- clientA.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee,
                                               msg.changeOutputFee)
      registerInputsB <- clientB.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee,
                                               msg.changeOutputFee)
      blindSigA <- coordinator.registerAlice(peerIdA, registerInputsA)
      blindSigB <- coordinator.registerAlice(peerIdB, registerInputsB)

      registerMixOutputA = clientA.processBlindOutputSig(blindSigA)
      registerMixOutputB = clientB.processBlindOutputSig(blindSigB)

      _ <- coordinator.beginOutputRegistration()

      _ <- coordinator.verifyAndRegisterBob(registerMixOutputA)
      _ <- coordinator.verifyAndRegisterBob(registerMixOutputB)

      // registering inputs and outputs will make it construct the unsigned psbt
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.currentRound().map(_.psbtOpt.isDefined),
        interval = 100.milliseconds,
        maxTries = 500)
      psbt <- coordinator.currentRound().map(_.psbtOpt.get)

      signedA <- clientA.validateAndSignPsbt(psbt)
      signedB <- clientB.validateAndSignPsbt(psbt)

      // do async so they can complete
      regAF = coordinator.registerPSBTSignatures(peerIdA, signedA)
      regBF = coordinator.registerPSBTSignatures(peerIdB, signedB)

      tx <- regAF
      tx2 <- regBF
      _ = assert(tx == tx2)

      inputUtxos = (utxosA ++ utxosB).filter(t =>
        tx.inputs.map(_.previousOutput).contains(t.outPoint))
      inputAmt = inputUtxos.map(_.amount).sum
      // regtest uses 1 sat/vbyte fee
      _ = assert(SatoshisPerVirtualByte.calc(inputAmt, tx).toLong == 1)

      height <- coordinator.bitcoind.getBlockCount

      // correct meta data
      _ = assert(tx.lockTime.toInt == height + 1)
      _ = assert(tx.version == Int32.two)
      // check correct inputs
      _ = (coinsA ++ coinsB).foreach { utxo =>
        assert(tx.inputs.exists(_.previousOutput == utxo.outPoint))
      }
      // check correct outputs
      _ = assert(tx.outputs.exists(o =>
        o.scriptPubKey == addrA.scriptPubKey && o.value == roundAmount))
      _ = assert(tx.outputs.exists(o =>
        o.scriptPubKey == addrB.scriptPubKey && o.value == roundAmount))

      // remix checks
      _ = txidOpt match {
        case Some(txid) =>
          assert(tx.inputs.exists(_.previousOutput.txIdBE == txid))
          assert(tx.outputs.count(_.value == roundAmount) == 2)
          assert(tx.outputs.count(_.value != roundAmount) == 2)
          assert(
            tx.outputs.count(_.value == coordinator.mixDetails.coordinatorFee) == 1)
          // 2 mix outputs + 1 change + coordinator fee
          assert(tx.outputs.size == 4)
        case None =>
          assert(tx.outputs.count(_.value == roundAmount) == 2)
          assert(tx.outputs.count(_.value != roundAmount) == 3)
          assert(
            tx.outputs.count(
              _.value == coordinator.mixDetails.coordinatorFee * Satoshis(2)) == 1)
          // 2 mix outputs + 2 change + coordinator fee
          assert(tx.outputs.size == 5)
      }

      _ <- clientA.completeRound(tx)
      _ <- clientB.completeRound(tx)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until new set of utxos
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientA.listCoins().map(_ != utxosA),
        interval = 100.milliseconds,
        maxTries = 500)
      // wait until new set of utxos
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientB.listCoins().map(_ != utxosB),
        interval = 100.milliseconds,
        maxTries = 500)

      coinsA <- clientA.listCoins()
      _ = txidOpt match {
        case Some(txid) =>
          // prev change
          val prev = coinsA.filter(_.outPoint.txIdBE == txid)
          assert(prev.size == 1 && prev.head.amount != roundAmount)

          // mix output
          assert(
            coinsA
              .find(_.outPoint.txIdBE == tx.txIdBE)
              .exists(_.amount == roundAmount))
        case None =>
          val roundOutputs = coinsA.filter(_.outPoint.txIdBE == tx.txIdBE)
          assert(roundOutputs.size == 2) // output + change
          // mix output
          assert(roundOutputs.exists(_.amount == roundAmount))
          // change output
          assert(roundOutputs.exists(_.amount != roundAmount))
      }

      coinsB <- clientB.listCoins()
      roundOutputs = coinsB.filter(_.outPoint.txIdBE == tx.txIdBE)
      _ = assert(roundOutputs.size == 2) // output + change
      // mix output
      _ = assert(roundOutputs.exists(_.amount == roundAmount))
      // change output
      _ = assert(roundOutputs.exists(_.amount != roundAmount))
    } yield tx
  }
}
