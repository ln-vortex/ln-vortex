package com.lnvortex.server.coordinator

import akka.testkit.TestActorRef
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import com.lnvortex.core.gen.Generators
import com.lnvortex.server.models.{RegisteredInputDb, RegisteredOutputDb}
import com.lnvortex.testkit.VortexCoordinatorFixture
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddressType
import org.bitcoins.core.currency._
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._
import org.bitcoins.rpc.BitcoindException.InvalidAddressOrKey
import org.bitcoins.testkitcore.Implicits.GeneratorOps

import scala.concurrent.Future

class VortexCoordinatorTest extends VortexCoordinatorFixture {
  behavior of "VortexCoordinator"

  it must "has the proper variable set after creating a new round" in {
    coordinator =>
      // coordinator should already have a round from _.start()
      for {
        dbOpt <- coordinator.roundDAO.read(coordinator.getCurrentRoundId)
      } yield {
        assert(dbOpt.isDefined)
        assert(coordinator.outputFee != Satoshis.zero)
      }
  }

  it must "register inputs" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.getCurrentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
      addr <- bitcoind.getNewAddress
      change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef), blind, change)

      _ <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
    } yield succeed
  }

  it must "fail to register inputs if invalid peerId" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.getCurrentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
      addr <- bitcoind.getNewAddress
      change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef), blind, change)

      res <- recoverToSucceededIf[IllegalArgumentException](
        coordinator.registerAlice(
          // wrong peerId
          Sha256Digest(
            "ded8ab0e14ee02492b1008f72a0a3a5abac201c731b7e71a92d36dc2db160d53"),
          registerInputs))
    } yield res
  }

  it must "fail to register inputs with invalid proofs" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    val genInputs = Generators.registerInputs.sampleSome
    for {
      unspent <- bitcoind.listUnspent
      inputRefs = unspent.map { utxo =>
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)

        InputReference(outpoint,
                       output,
                       P2WPKHWitnessV0(ECPublicKey.freshPublicKey))
      }
      res <- recoverToSucceededIf[RuntimeException](
        coordinator.registerAlice(Sha256Digest.empty,
                                  genInputs.copy(inputs = inputRefs)))
    } yield res
  }

  it must "fail to register inputs with that don't exist" in { coordinator =>
    val genInputs = Generators.registerInputs.sampleSome
    for {
      res <- recoverToSucceededIf[InvalidAddressOrKey](
        coordinator.registerAlice(Sha256Digest.empty, genInputs))
    } yield res
  }

  it must "fail register inputs with a legacy change address" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        TestActorRef("test"),
                                        AskNonce(coordinator.getCurrentRoundId))
        _ <- coordinator.beginInputRegistration()

        utxo <- bitcoind.listUnspent.map(_.head)
        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
        // wrong address type
        addr <- bitcoind.getNewAddress(AddressType.Legacy)
        change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

        blind = ECPrivateKey.freshPrivateKey.fieldElement
        registerInputs = RegisterInputs(Vector(inputRef), blind, change)

        res <- recoverToSucceededIf[IllegalArgumentException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }

  it must "fail register inputs with a p2sh change address" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.getCurrentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
      // wrong change addr
      addr <- bitcoind.getNewAddress(AddressType.P2SHSegwit)
      change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef), blind, change)

      res <- recoverToSucceededIf[IllegalArgumentException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail register inputs with a invalid change amount" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        TestActorRef("test"),
                                        AskNonce(coordinator.getCurrentRoundId))
        _ <- coordinator.beginInputRegistration()

        utxo <- bitcoind.listUnspent.map(_.head)
        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
        addr <- bitcoind.getNewAddress
        // wrong amount
        change = TransactionOutput(Bitcoins(50), addr.scriptPubKey)

        blind = ECPrivateKey.freshPrivateKey.fieldElement
        registerInputs = RegisterInputs(Vector(inputRef), blind, change)

        res <- recoverToSucceededIf[IllegalArgumentException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }

  it must "fail register inputs with a invalid blind proof" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.getCurrentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
      addr <- bitcoind.getNewAddress
      change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

      // wrong blind proof
      blind = FieldElement.zero
      registerInputs = RegisterInputs(Vector(inputRef), blind, change)

      res <- recoverToSucceededIf[IllegalArgumentException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "register an output" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.getCurrentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
      addr <- bitcoind.getNewAddress
      change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

      tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                   signerNonce = aliceDb.nonce)

      p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
      mixOutput = TransactionOutput(coordinator.config.mixAmount, p2wsh)
      challenge = BobMessage.calculateChallenge(mixOutput,
                                                coordinator.getCurrentRoundId)
      blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                 aliceDb.nonce,
                                                 tweaks,
                                                 challenge)

      registerInputs = RegisterInputs(Vector(inputRef), blind, change)
      blindSig <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                              coordinator.publicKey,
                                              aliceDb.nonce,
                                              tweaks,
                                              challenge)
      _ <- coordinator.beginOutputRegistration()
      _ <- coordinator.verifyAndRegisterBob(BobMessage(sig, mixOutput))
    } yield succeed
  }

  it must "fail to register an output with an invalid sig" in { coordinator =>
    for {
      _ <- coordinator.beginOutputRegistration()
      p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
      mixOutput = TransactionOutput(coordinator.config.mixAmount, p2wsh)
      // random sig
      sig = ECPrivateKey.freshPrivateKey.schnorrSign(Sha256Digest.empty.bytes)
      res <- recoverToSucceededIf[IllegalArgumentException](
        coordinator.verifyAndRegisterBob(BobMessage(sig, mixOutput)))
    } yield res
  }

  it must "fail to register a non-p2wsh output" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.getCurrentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
      addr <- bitcoind.getNewAddress
      change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

      tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                   signerNonce = aliceDb.nonce)

      // wrong spk
      mixOutput = TransactionOutput(coordinator.config.mixAmount,
                                    addr.scriptPubKey)
      challenge = BobMessage.calculateChallenge(mixOutput,
                                                coordinator.getCurrentRoundId)
      blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                 aliceDb.nonce,
                                                 tweaks,
                                                 challenge)

      registerInputs = RegisterInputs(Vector(inputRef), blind, change)
      blindSig <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                              coordinator.publicKey,
                                              aliceDb.nonce,
                                              tweaks,
                                              challenge)
      _ <- coordinator.beginOutputRegistration()
      res <- recoverToSucceededIf[IllegalArgumentException](
        coordinator.verifyAndRegisterBob(BobMessage(sig, mixOutput)))
    } yield res
  }

  it must "fail to register an output with the wrong roundId" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        TestActorRef("test"),
                                        AskNonce(coordinator.getCurrentRoundId))
        _ <- coordinator.beginInputRegistration()

        utxo <- bitcoind.listUnspent.map(_.head)
        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
        addr <- bitcoind.getNewAddress
        change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

        tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                     signerNonce = aliceDb.nonce)

        p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
        mixOutput = TransactionOutput(coordinator.config.mixAmount, p2wsh)
        // wrong roundId
        challenge = BobMessage.calculateChallenge(mixOutput,
                                                  DoubleSha256Digest.empty)
        blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                   aliceDb.nonce,
                                                   tweaks,
                                                   challenge)

        registerInputs = RegisterInputs(Vector(inputRef), blind, change)
        blindSig <- coordinator.registerAlice(Sha256Digest.empty,
                                              registerInputs)
        sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                coordinator.publicKey,
                                                aliceDb.nonce,
                                                tweaks,
                                                challenge)
        _ <- coordinator.beginOutputRegistration()
        res <- recoverToSucceededIf[IllegalArgumentException](
          coordinator.verifyAndRegisterBob(BobMessage(sig, mixOutput)))
      } yield res
  }

  it must "construct the unsigned coinjoin tx" in { coordinator =>
    val peerIds =
      0.to(5)
        .map(_ => CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes))
        .toVector

    for {
      _ <- coordinator.beginOutputRegistration()
      aliceDbFs = peerIds.map(peerId =>
        coordinator.getNonce(peerId,
                             TestActorRef(peerId.hex),
                             AskNonce(coordinator.getCurrentRoundId)))
      aliceDbs <- Future.sequence(aliceDbFs)
      updatedAliceDbs = aliceDbs.map { db =>
        val spk = P2WPKHWitnessSPKV0(ECPublicKey.freshPublicKey)
        val output = TransactionOutput(Bitcoins(1), spk)
        db.setOutputValues(1,
                           ECPrivateKey.freshPrivateKey.fieldElement,
                           output,
                           ECPrivateKey.freshPrivateKey.fieldElement)
      }
      _ <- coordinator.aliceDAO.updateAll(updatedAliceDbs)

      inputDbs = peerIds.map { id =>
        val outPoint = TransactionOutPoint(
          CryptoUtil.doubleSHA256(ECPrivateKey.freshPrivateKey.bytes),
          UInt32.zero)
        val spk = P2WPKHWitnessSPKV0(ECPublicKey.freshPublicKey)
        val output = TransactionOutput(Bitcoins(1), spk)
        RegisteredInputDb(outPoint = outPoint,
                          output = output,
                          inputProof = EmptyScriptWitness,
                          indexOpt = None,
                          roundId = coordinator.getCurrentRoundId,
                          peerId = id)
      }
      _ <- coordinator.inputsDAO.createAll(inputDbs)

      outputDbs = peerIds.map { _ =>
        val raw = P2PKHScriptPubKey(ECPublicKey.freshPublicKey)
        val spk = P2WSHWitnessSPKV0(raw)
        val output = TransactionOutput(coordinator.config.mixAmount, spk)
        val sig = ECPrivateKey.freshPrivateKey.schnorrSign(
          CryptoUtil.sha256(raw.bytes).bytes)

        RegisteredOutputDb(output, sig, coordinator.getCurrentRoundId)
      }

      _ <- coordinator.outputsDAO.createAll(outputDbs)

      addr <- coordinator.bitcoind.getNewAddress
      tx <- coordinator.constructUnsignedPSBT(addr).map(_.transaction)

      inputDbs <- coordinator.inputsDAO.findByRoundId(
        coordinator.getCurrentRoundId)
    } yield {
      assert(inputDbs.forall(_.indexOpt.isDefined))
      val correctOutpoints = inputDbs.forall { db =>
        val index = db.indexOpt.get
        tx.inputs(index).previousOutput == db.outPoint
      }
      assert(correctOutpoints)

      assert(outputDbs.forall(d => tx.outputs.contains(d.output)))

      val hasChangeOutputs = updatedAliceDbs.forall { db =>
        tx.outputs.contains(db.changeOutputOpt.get)
      }
      assert(hasChangeOutputs)

      val mixFee = coordinator.config.mixFee * peerIds.size
      val mixOutput = TransactionOutput(mixFee, addr.scriptPubKey)
      assert(tx.outputs.contains(mixOutput))
    }
  }

  it must "register a psbt signature" in { coordinator =>
    val peerId = Sha256Digest.empty
    val bitcoind = coordinator.bitcoind

    for {
      _ <- coordinator.beginOutputRegistration()
      aliceDb <- coordinator.getNonce(peerId,
                                      TestActorRef(peerId.hex),
                                      AskNonce(coordinator.getCurrentRoundId))

      addr <- bitcoind.getNewAddress
      unspent <- bitcoind.listUnspent.map(_.head)

      updatedAliceDbs = {
        val output = TransactionOutput(Bitcoins(1), addr.scriptPubKey)
        aliceDb.setOutputValues(1,
                                ECPrivateKey.freshPrivateKey.fieldElement,
                                output,
                                ECPrivateKey.freshPrivateKey.fieldElement)
      }
      _ <- coordinator.aliceDAO.update(updatedAliceDbs)

      inputDb = {
        val spk = unspent.scriptPubKey.get
        val outPoint = TransactionOutPoint(unspent.txid, UInt32(unspent.vout))
        val output = TransactionOutput(unspent.amount, spk)
        RegisteredInputDb(outPoint = outPoint,
                          output = output,
                          inputProof = EmptyScriptWitness,
                          indexOpt = None,
                          roundId = coordinator.getCurrentRoundId,
                          peerId = peerId)
      }
      _ <- coordinator.inputsDAO.create(inputDb)

      outputDb = {
        val raw = P2PKHScriptPubKey(ECPublicKey.freshPublicKey)
        val spk = P2WSHWitnessSPKV0(raw)
        val output = TransactionOutput(coordinator.config.mixAmount, spk)
        val sig = ECPrivateKey.freshPrivateKey.schnorrSign(
          CryptoUtil.sha256(raw.bytes).bytes)

        RegisteredOutputDb(output, sig, coordinator.getCurrentRoundId)
      }
      _ <- coordinator.outputsDAO.create(outputDb)

      addr <- coordinator.bitcoind.getNewAddress
      psbt <- coordinator.constructUnsignedPSBT(addr)

      signed <- bitcoind.walletProcessPSBT(psbt)

      _ <- coordinator.registerPSBTSignatures(peerId, signed.psbt)
    } yield succeed
  }

}
