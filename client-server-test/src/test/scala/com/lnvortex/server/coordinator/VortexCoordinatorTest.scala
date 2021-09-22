package com.lnvortex.server.coordinator

import akka.testkit.TestActorRef
import com.lnvortex.core.{AskNonce, InputReference, RegisterInputs}
import com.lnvortex.core.gen.Generators
import com.lnvortex.server.VortexCoordinatorFixture
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddressType
import org.bitcoins.core.currency._
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.script.P2WPKHWitnessV0
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._
import org.bitcoins.rpc.BitcoindException.InvalidAddressOrKey
import org.bitcoins.testkitcore.Implicits.GeneratorOps

class VortexCoordinatorTest extends VortexCoordinatorFixture {
  behavior of "VortexCoordinator"

  it must "has the proper variable set after creating a new round" in {
    coordinator =>
      // coordinator should already have a round from _.start()
      for {
        dbOpt <- coordinator.roundDAO.read(coordinator.currentRoundId)
      } yield {
        assert(dbOpt.isDefined)
        assert(coordinator.beginInputRegistrationCancellable.isDefined)
        assert(coordinator.outputFee != Satoshis.zero)
      }
  }

  it must "register inputs" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      nonce <- coordinator.getNonce(Sha256Digest.empty,
                                    TestActorRef("test"),
                                    AskNonce(coordinator.currentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, nonce.schnorrNonce)
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
      nonce <- coordinator.getNonce(Sha256Digest.empty,
                                    TestActorRef("test"),
                                    AskNonce(coordinator.currentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, nonce.schnorrNonce)
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
        nonce <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.currentRoundId))
        _ <- coordinator.beginInputRegistration()

        utxo <- bitcoind.listUnspent.map(_.head)
        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        tx = InputReference.constructInputProofTx(outputRef, nonce.schnorrNonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
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
      nonce <- coordinator.getNonce(Sha256Digest.empty,
                                    TestActorRef("test"),
                                    AskNonce(coordinator.currentRoundId))
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, nonce.schnorrNonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
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
        nonce <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.currentRoundId))
        _ <- coordinator.beginInputRegistration()

        utxo <- bitcoind.listUnspent.map(_.head)
        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        tx = InputReference.constructInputProofTx(outputRef, nonce.schnorrNonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
        addr <- bitcoind.getNewAddress
        change = TransactionOutput(Bitcoins(50), addr.scriptPubKey)

        blind = ECPrivateKey.freshPrivateKey.fieldElement
        registerInputs = RegisterInputs(Vector(inputRef), blind, change)

        res <- recoverToSucceededIf[IllegalArgumentException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }

  it must "fail register inputs with a invalid blind proof" ignore {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        nonce <- coordinator.getNonce(Sha256Digest.empty,
                                      TestActorRef("test"),
                                      AskNonce(coordinator.currentRoundId))
        _ <- coordinator.beginInputRegistration()

        utxo <- bitcoind.listUnspent.map(_.head)
        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        tx = InputReference.constructInputProofTx(outputRef, nonce.schnorrNonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
        addr <- bitcoind.getNewAddress
        change = TransactionOutput(Satoshis(4998989765L), addr.scriptPubKey)

        blind = FieldElement.zero
        registerInputs = RegisterInputs(Vector(inputRef), blind, change)

        res <- recoverToSucceededIf[IllegalArgumentException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }
}
