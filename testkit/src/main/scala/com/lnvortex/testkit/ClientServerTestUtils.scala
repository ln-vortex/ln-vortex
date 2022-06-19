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
import org.bitcoins.crypto.{DoubleSha256DigestBE, FieldElement, Sha256Digest}
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

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
      // don't select all coins
      utxos <- client.listCoins().map(_.tail)
      _ = client.queueCoins(utxos.map(_.outputReference), nodeId, None)
      msg <- coordinator.beginInputRegistration()

      registerInputs <- client.registerCoins(msg.roundId,
                                             msg.inputFee,
                                             msg.outputFee)
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
      // don't select all coins
      utxos <- client.listCoins().map(_.tail)
      addr <- client.vortexWallet.getNewAddress(
        coordinator.mixDetails.outputType)
      _ = client.queueCoins(utxos.map(_.outputReference), addr)
      msg <- coordinator.beginInputRegistration()

      registerInputs <- client.registerCoins(msg.roundId,
                                             msg.inputFee,
                                             msg.outputFee)
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

      // don't select all coins
      utxos <- clientA.listCoins().map(_.tail)
      _ = clientA.queueCoins(utxos.map(_.outputReference), nodeIdB, None)

      // don't select all coins
      utxos <- clientB.listCoins().map(_.tail)
      _ = clientB.queueCoins(utxos.map(_.outputReference), nodeIdA, None)

      msg <- coordinator.beginInputRegistration()

      registerInputsA <- clientA.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee)
      blindSigA <- coordinator.registerAlice(peerIdA, registerInputsA)

      registerInputsB <- clientB.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee)
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
      psbt <- signPSBT(peerId, client, coordinator, peerLnd)

      _ <- coordinator.registerPSBTSignatures(peerId, psbt)

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

      _ <- coordinator.registerPSBTSignatures(peerId, psbt)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until new set of utxos
      _ <- TestAsyncUtil.awaitConditionF(() => client.listCoins().map(_ != all),
                                         interval = 100.milliseconds,
                                         maxTries = 500)
    } yield ()
  }

  def completeMixRound(
      peerIdA: Sha256Digest,
      peerIdB: Sha256Digest,
      clientA: VortexClient[LndVortexWallet],
      clientB: VortexClient[LndVortexWallet],
      coordinator: VortexCoordinator)(implicit
      ec: ExecutionContext): Future[Unit] = {
    for {
      (psbtA, psbtB) <- signPSBT(peerIdA,
                                 peerIdB,
                                 clientA,
                                 clientB,
                                 coordinator)

      // do async so they can complete
      regAF = coordinator.registerPSBTSignatures(peerIdA, psbtA)
      regBF = coordinator.registerPSBTSignatures(peerIdB, psbtB)

      _ <- regAF
      _ <- regBF

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
        () => clientA.vortexWallet.lndRpcClient.listChannels().map(_.nonEmpty),
        interval = 100.milliseconds,
        maxTries = 500)
    } yield ()
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
          case None => coins.tail
        }
      }
      addrA <- clientA.vortexWallet.getNewAddress(
        coordinator.mixDetails.outputType)
      _ = clientA.queueCoins(utxosA.map(_.outputReference), addrA)
      // select non-remix coins
      utxosB <- clientB.listCoins().map { coins =>
        txidOpt match {
          case Some(txid) =>
            coins.filterNot(_.outPoint.txIdBE == txid)
          case None => coins.tail
        }
      }
      addrB <- clientB.vortexWallet.getNewAddress(
        coordinator.mixDetails.outputType)
      _ = clientB.queueCoins(utxosB.map(_.outputReference), addrB)
      msg <- coordinator.beginInputRegistration()

      registerInputsA <- clientA.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee)
      registerInputsB <- clientB.registerCoins(msg.roundId,
                                               msg.inputFee,
                                               msg.outputFee)
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

      height <- coordinator.bitcoind.getBlockCount

      // correct meta data
      _ = assert(tx.lockTime.toInt == height + 1)
      _ = assert(tx.version == Int32.two)
      // check correct inputs
      _ = (utxosA ++ utxosB).foreach { utxo =>
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
            tx.outputs.count(_.value == coordinator.mixDetails.mixFee) == 1)
          // 2 mix outputs + 1 change + coordinator fee
          assert(tx.outputs.size == 4)
        case None =>
          assert(tx.outputs.count(_.value == roundAmount) == 2)
          assert(tx.outputs.count(_.value != roundAmount) == 3)
          assert(
            tx.outputs.count(
              _.value == coordinator.mixDetails.mixFee * Satoshis(2)) == 1)
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
