package com.lnvortex.server.coordinator

import akka.http.scaladsl.model.ws.Message
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import com.lnvortex.core.gen.Generators
import com.lnvortex.server.VortexServerException._
import com.lnvortex.server.models._
import com.lnvortex.testkit.VortexCoordinatorFixture
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddressType
import org.bitcoins.core.currency._
import org.bitcoins.core.number.{Int32, UInt32}
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkitcore.Implicits.GeneratorOps

import scala.concurrent.Future

class VortexCoordinatorTest extends VortexCoordinatorFixture with EmbeddedPg {
  behavior of "VortexCoordinator"

  val badPeerId: Sha256Digest = Sha256Digest(
    "ded8ab0e14ee02492b1008f72a0a3a5abac201c731b7e71a92d36dc2db160d53")

  private def dummyQueue(): SourceQueueWithComplete[Message] =
    Source
      .queue[Message](bufferSize = 10,
                      OverflowStrategy.backpressure,
                      maxConcurrentOffers = 2)
      .toMat(BroadcastHub.sink)(Keep.left)
      .run()

  it must "has the proper variable set after creating a new round" in {
    coordinator =>
      // coordinator should already have a round from _.start()
      for {
        dbOpt <- coordinator.roundDAO.read(coordinator.getCurrentRoundId)
      } yield {
        assert(dbOpt.isDefined)
      }
  }

  it must "have get nonce be idempotent" in { coordinator =>
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
      aliceDb2 <- coordinator.getNonce(Sha256Digest.empty,
                                       dummyQueue(),
                                       coordinator.getCurrentRoundId)
    } yield assert(aliceDb == aliceDb2)
  }

  it must "fail to get a nonce for a different round" in { coordinator =>
    val ex = intercept[IllegalArgumentException](
      coordinator
        .getNonce(Sha256Digest.empty, dummyQueue(), DoubleSha256Digest.empty))

    assert(ex.getMessage.contains("different roundId"))
  }

  it must "fail to get a nonce with a previously used peerId" in {
    coordinator =>
      for {
        _ <- coordinator.getNonce(Sha256Digest.empty,
                                  dummyQueue(),
                                  coordinator.getCurrentRoundId)
        _ <- coordinator.cancelRound()
        nextCoord <- coordinator.getNextCoordinator
        ex <- recoverToExceptionIf[RuntimeException](
          nextCoord.getNonce(Sha256Digest.empty,
                             dummyQueue(),
                             nextCoord.getCurrentRoundId))
      } yield assert(
        ex.getMessage == s"Alice ${Sha256Digest.empty.hex} asked for" +
          s" nonce of round ${nextCoord.getCurrentRoundId.hex} but is in round ${coordinator.getCurrentRoundId.hex}")
  }

  it must "successfully register inputs" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    val (queue, source) = Source
      .queue[Message](bufferSize = 10,
                      OverflowStrategy.backpressure,
                      maxConcurrentOffers = 2)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val sink = source.runWith(Sink.seq)

    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      queue,
                                      coordinator.getCurrentRoundId)
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

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      _ <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      _ = queue.complete()
      msgs <- sink
    } yield assert(msgs.size == 1)
  }

  it must "fail to register banned inputs" in { coordinator =>
    val bitcoind = coordinator.bitcoind

    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      banDb = BannedUtxoDb(outputRef.outPoint,
                           TimeUtil.now.plusSeconds(100),
                           "test")
      _ <- coordinator.bannedUtxoDAO.create(banDb)

      res <- recoverToExceptionIf[InvalidInputsException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield assert(res.getMessage.contains("is currently banned"))
  }

  it must "fail to input with malicious vout" in { coordinator =>
    val bitcoind = coordinator.bitcoind

    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      // set vout to UInt32.max to try and get an index out of bounds exception
      badOutputRef = OutputReference(outputRef.outPoint.copy(vout = UInt32.max),
                                     outputRef.output)
      inputRef = InputReference(badOutputRef, proof)
      addr <- bitcoind.getNewAddress

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToExceptionIf[InvalidInputsException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield assert(res.getMessage.contains("does not exist on the blockchain"))
  }

  it must "fail to register inputs without minimal utxos" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
      _ <- coordinator.beginInputRegistration()

      utxos <- bitcoind.listUnspent.map(_.map { utxo =>
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      })
      inputRefFs = utxos.map { outputRef =>
        val tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
        bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx)).map { signed =>
          val proof =
            signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness
          InputReference(outputRef, proof)
        }
      }

      inputRefs <- Future.sequence(inputRefFs)
      addr <- bitcoind.getNewAddress

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(inputRefs, blind, Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[NonMinimalInputsException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail to register inputs with duplicate change addresses" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
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

        blind = ECPrivateKey.freshPrivateKey.fieldElement
        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        utxo.scriptPubKey)

        res <- recoverToSucceededIf[AttemptedAddressReuseException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }

  it must "fail to register inputs with reused addresses" in { coordinator =>
    val bitcoind = coordinator.bitcoind

    val otherPeerId = Sha256Digest(
      "8140e6dbfe062fb23ec84a3f2c5ec19b5e0566cfd0b88082a7042ec82d1a6593")

    for {
      _ <- coordinator.getNonce(otherPeerId,
                                dummyQueue(),
                                coordinator.getCurrentRoundId)
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
      _ <- coordinator
        .beginInputRegistration()
        .recover(_ => ())

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }

      // create a db with the same spk
      badDb = RegisteredInputDb(
        EmptyTransactionOutPoint,
        outputRef.output,
        EmptyScriptWitness,
        None,
        coordinator.getCurrentRoundId,
        otherPeerId
      )
      _ <- coordinator.inputsDAO.create(badDb)

      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      utxo.scriptPubKey)

      _ <- coordinator.getAskInputsMessage

      res <- recoverToSucceededIf[InvalidInputsException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail to register inputs with wrong script types" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
      _ <- coordinator.beginInputRegistration()

      legacyAddr <- bitcoind.getNewAddress(AddressType.Legacy)

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, legacyAddr.scriptPubKey)
        OutputReference(outpoint, output)
      }
      tx = InputReference.constructInputProofTx(outputRef, aliceDb.nonce)
      signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
      proof =
        signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

      inputRef = InputReference(outputRef, proof)
      addr <- bitcoind.getNewAddress

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[InvalidInputsException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail to register inputs with not enough funding" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
      _ <- coordinator.beginInputRegistration()

      // send to self for small utxo
      amt = Satoshis(5000)
      addr <- bitcoind.getNewAddress
      _ <- bitcoind.sendToAddress(addr, amt)
      _ <- bitcoind.generateToAddress(6, addr)

      // get small utxo
      utxos <- bitcoind.listUnspent
      utxo = utxos.find(_.amount == amt).get
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

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[NotEnoughFundingException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail to register unconfirmed inputs" in { coordinator =>
    val bitcoind = coordinator.bitcoind

    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
      _ <- coordinator.beginInputRegistration()

      // send to self for unconfirmed tx
      addr <- bitcoind.getNewAddress
      _ <- bitcoind.sendToAddress(addr, Bitcoins.one)

      utxo <- bitcoind.listUnspent(0, 0).map(_.head)
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

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[InvalidInputsException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail register inputs when committing to the wrong nonce" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        _ <- coordinator.getNonce(Sha256Digest.empty,
                                  dummyQueue(),
                                  coordinator.getCurrentRoundId)
        _ <- coordinator.beginInputRegistration()

        utxo <- bitcoind.listUnspent.map(_.head)
        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        // wrong nonce
        tx = InputReference.constructInputProofTx(
          outputRef,
          ECPrivateKey.freshPrivateKey.schnorrNonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
        addr <- bitcoind.getNewAddress

        blind = ECPrivateKey.freshPrivateKey.fieldElement
        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))

        res <- recoverToSucceededIf[InvalidInputsException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }

  it must "fail register inputs when committing to the wrong outpoint" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
        _ <- coordinator.beginInputRegistration()

        // mine some blocks so bitcoind has more than one utxo
        _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))

        utxos <- bitcoind.listUnspent
        utxo = utxos.head
        wrongUtxo = utxos.last
        _ = require(utxo != wrongUtxo)

        outputRef = {
          val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
          val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
          OutputReference(outpoint, output)
        }
        // wrong outpoint
        tx = InputReference.constructInputProofTx(
          TransactionOutPoint(wrongUtxo.txid, UInt32(wrongUtxo.vout)),
          aliceDb.nonce)
        signed <- bitcoind.walletProcessPSBT(PSBT.fromUnsignedTx(tx))
        proof =
          signed.psbt.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness

        inputRef = InputReference(outputRef, proof)
        addr <- bitcoind.getNewAddress

        blind = ECPrivateKey.freshPrivateKey.fieldElement
        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))

        res <- recoverToSucceededIf[InvalidInputsException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }

  it must "fail to register inputs if invalid peerId" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[IllegalArgumentException](
        coordinator.registerAlice(
          // wrong peerId
          badPeerId,
          registerInputs))
    } yield res
  }

  it must "fail to register inputs with invalid proofs" in { coordinator =>
    val bitcoind = coordinator.bitcoind

    for {
      _ <- coordinator.getNonce(Sha256Digest.empty,
                                dummyQueue(),
                                coordinator.getCurrentRoundId)
      _ <- coordinator.beginInputRegistration()

      utxo <- bitcoind.listUnspent.map(_.head)
      outputRef = {
        val outpoint = TransactionOutPoint(utxo.txid, UInt32(utxo.vout))
        val output = TransactionOutput(utxo.amount, utxo.scriptPubKey.get)
        OutputReference(outpoint, output)
      }
      proof = P2WPKHWitnessV0(ECPublicKey.freshPublicKey)

      inputRef = InputReference(outputRef, proof)
      addr <- bitcoind.getNewAddress

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[InvalidInputsException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail to register inputs when in an invalid state" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)

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

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[IllegalStateException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail to register inputs that don't exist" in { coordinator =>
    val genInputs = Generators.registerInputs.sampleSome
    for {
      _ <- coordinator.getNonce(Sha256Digest.empty,
                                dummyQueue(),
                                coordinator.getCurrentRoundId)
      _ <- coordinator.beginInputRegistration()
      res <- recoverToSucceededIf[InvalidInputsException](
        coordinator.registerAlice(Sha256Digest.empty, genInputs))
    } yield res
  }

  it must "fail register inputs with a legacy change address" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
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

        blind = ECPrivateKey.freshPrivateKey.fieldElement
        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))

        res <- recoverToSucceededIf[InvalidChangeScriptPubKeyException](
          coordinator.registerAlice(Sha256Digest.empty, registerInputs))
      } yield res
  }

  it must "fail register inputs with a p2sh change address" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      blind = ECPrivateKey.freshPrivateKey.fieldElement
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[InvalidChangeScriptPubKeyException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "fail register inputs with a invalid blind proof" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      // wrong blind proof
      blind = FieldElement.zero
      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))

      res <- recoverToSucceededIf[InvalidBlindChallengeException](
        coordinator.registerAlice(Sha256Digest.empty, registerInputs))
    } yield res
  }

  it must "register an output" in { coordinator =>
    val bitcoind = coordinator.bitcoind

    val (queue, source) = Source
      .queue[Message](bufferSize = 10,
                      OverflowStrategy.backpressure,
                      maxConcurrentOffers = 2)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val sink = source.runWith(Sink.seq)

    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      queue,
                                      coordinator.getCurrentRoundId)
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

      tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                   signerNonce = aliceDb.nonce)

      p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
      targetOutput = TransactionOutput(coordinator.config.roundAmount, p2wsh)
      challenge = RegisterOutput.calculateChallenge(
        targetOutput,
        coordinator.getCurrentRoundId)
      blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                 aliceDb.nonce,
                                                 tweaks,
                                                 challenge)

      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))
      blindSig <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                              coordinator.publicKey,
                                              aliceDb.nonce,
                                              tweaks,
                                              challenge)
      _ <- coordinator.beginOutputRegistration()
      _ = queue.complete()
      msgs <- sink
      _ <- coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput))
    } yield assert(msgs.size == 2)
  }

  it must "fail to replay a blind signature" in { coordinator =>
    val bitcoind = coordinator.bitcoind

    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                   signerNonce = aliceDb.nonce)

      p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
      p2wsh2 = P2WSHWitnessSPKV0(P2PKHScriptPubKey(ECPublicKey.freshPublicKey))
      targetOutput = TransactionOutput(coordinator.config.roundAmount, p2wsh)
      targetOutput2 = TransactionOutput(coordinator.config.roundAmount, p2wsh2)
      challenge = RegisterOutput.calculateChallenge(
        targetOutput,
        coordinator.getCurrentRoundId)
      blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                 aliceDb.nonce,
                                                 tweaks,
                                                 challenge)

      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))
      blindSig <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                              coordinator.publicKey,
                                              aliceDb.nonce,
                                              tweaks,
                                              challenge)
      _ <- coordinator.beginOutputRegistration()
      _ <- coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput))
      _ <- recoverToSucceededIf[InvalidOutputSignatureException](
        coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput2)))
    } yield succeed
  }

  it must "fail to register an output with a duplicate address" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind

      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
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

        tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                     signerNonce = aliceDb.nonce)

        p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
        targetOutput = TransactionOutput(coordinator.config.roundAmount, p2wsh)
        challenge = RegisterOutput.calculateChallenge(
          targetOutput,
          coordinator.getCurrentRoundId)
        blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                   aliceDb.nonce,
                                                   tweaks,
                                                   challenge)

        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))
        blindSig <- coordinator.registerAlice(Sha256Digest.empty,
                                              registerInputs)
        sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                coordinator.publicKey,
                                                aliceDb.nonce,
                                                tweaks,
                                                challenge)
        _ <- coordinator.beginOutputRegistration()
        _ <- coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput))
        ex <- recoverToExceptionIf[InvalidTargetOutputScriptPubKeyException](
          coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
      } yield assert(ex.getMessage.contains("already registered as an output"))
  }

  it must "fail to register an output with an already used input address" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind

      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
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

        tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                     signerNonce = aliceDb.nonce)

        p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
        targetOutput = TransactionOutput(coordinator.config.roundAmount, p2wsh)
        challenge = RegisterOutput.calculateChallenge(
          targetOutput,
          coordinator.getCurrentRoundId)
        blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                   aliceDb.nonce,
                                                   tweaks,
                                                   challenge)

        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))
        blindSig <- coordinator.registerAlice(Sha256Digest.empty,
                                              registerInputs)
        sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                coordinator.publicKey,
                                                aliceDb.nonce,
                                                tweaks,
                                                challenge)
        _ <- coordinator.beginOutputRegistration()

        // add to input database
        inputDb = RegisteredInputDb(EmptyTransactionOutPoint,
                                    targetOutput,
                                    EmptyScriptWitness,
                                    None,
                                    coordinator.getCurrentRoundId,
                                    Sha256Digest.empty)
        _ <- coordinator.inputsDAO.create(inputDb)

        ex <- recoverToExceptionIf[InvalidTargetOutputScriptPubKeyException](
          coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
      } yield assert(ex.getMessage.contains("already registered as an input"))
  }

  it must "fail to register an output with an invalid sig" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                   signerNonce = aliceDb.nonce)

      p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
      targetOutput = TransactionOutput(coordinator.config.roundAmount, p2wsh)
      challenge = RegisterOutput.calculateChallenge(
        targetOutput,
        coordinator.getCurrentRoundId)
      blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                 aliceDb.nonce,
                                                 tweaks,
                                                 challenge)

      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))
      _ <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      _ <- coordinator.beginOutputRegistration()
      p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
      targetOutput = TransactionOutput(coordinator.config.roundAmount, p2wsh)
      // random sig
      sig = ECPrivateKey.freshPrivateKey.schnorrSign(Sha256Digest.empty.bytes)
      res <- recoverToSucceededIf[InvalidOutputSignatureException](
        coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
    } yield res
  }

  it must "fail to register an output with a reused change address" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
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

        tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                     signerNonce = aliceDb.nonce)

        // change change addr spk
        targetOutput = TransactionOutput(coordinator.config.roundAmount,
                                         addr.scriptPubKey)
        challenge = RegisterOutput.calculateChallenge(
          targetOutput,
          coordinator.getCurrentRoundId)
        blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                   aliceDb.nonce,
                                                   tweaks,
                                                   challenge)

        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))
        blindSig <- coordinator.registerAlice(Sha256Digest.empty,
                                              registerInputs)
        sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                coordinator.publicKey,
                                                aliceDb.nonce,
                                                tweaks,
                                                challenge)
        _ <- coordinator.beginOutputRegistration()
        res <- recoverToSucceededIf[InvalidTargetOutputScriptPubKeyException](
          coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
      } yield res
  }

  it must "fail to register an output with a reused input address" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
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

        tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                     signerNonce = aliceDb.nonce)

        // use input's spk
        targetOutput = TransactionOutput(coordinator.config.roundAmount,
                                         outputRef.output.scriptPubKey)
        challenge = RegisterOutput.calculateChallenge(
          targetOutput,
          coordinator.getCurrentRoundId)
        blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                   aliceDb.nonce,
                                                   tweaks,
                                                   challenge)

        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))
        blindSig <- coordinator.registerAlice(Sha256Digest.empty,
                                              registerInputs)
        sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                coordinator.publicKey,
                                                aliceDb.nonce,
                                                tweaks,
                                                challenge)
        _ <- coordinator.beginOutputRegistration()
        res <- recoverToSucceededIf[InvalidTargetOutputScriptPubKeyException](
          coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
      } yield res
  }

  it must "fail to register a non-p2wsh output" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                   signerNonce = aliceDb.nonce)

      // wrong spk
      targetOutput = TransactionOutput(coordinator.config.roundAmount,
                                       addr.scriptPubKey)
      challenge = RegisterOutput.calculateChallenge(
        targetOutput,
        coordinator.getCurrentRoundId)
      blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                 aliceDb.nonce,
                                                 tweaks,
                                                 challenge)

      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))
      blindSig <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                              coordinator.publicKey,
                                              aliceDb.nonce,
                                              tweaks,
                                              challenge)
      _ <- coordinator.beginOutputRegistration()
      res <- recoverToSucceededIf[InvalidTargetOutputScriptPubKeyException](
        coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
    } yield res
  }

  it must "fail to register an output with the wrong amount" in { coordinator =>
    val bitcoind = coordinator.bitcoind
    for {
      aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)
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

      tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                   signerNonce = aliceDb.nonce)

      p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
      targetOutput = TransactionOutput(Bitcoins.one, p2wsh)
      challenge = RegisterOutput.calculateChallenge(
        targetOutput,
        coordinator.getCurrentRoundId)
      blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                 aliceDb.nonce,
                                                 tweaks,
                                                 challenge)

      registerInputs = RegisterInputs(Vector(inputRef),
                                      blind,
                                      Some(addr.scriptPubKey))
      blindSig <- coordinator.registerAlice(Sha256Digest.empty, registerInputs)
      sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                              coordinator.publicKey,
                                              aliceDb.nonce,
                                              tweaks,
                                              challenge)
      _ <- coordinator.beginOutputRegistration()
      res <- recoverToSucceededIf[InvalidTargetOutputAmountException](
        coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
    } yield res
  }

  it must "fail to register an output with the wrong roundId" in {
    coordinator =>
      val bitcoind = coordinator.bitcoind
      for {
        aliceDb <- coordinator.getNonce(Sha256Digest.empty,
                                        dummyQueue(),
                                        coordinator.getCurrentRoundId)
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

        tweaks = freshBlindingTweaks(signerPubKey = coordinator.publicKey,
                                     signerNonce = aliceDb.nonce)

        p2wsh = P2WSHWitnessSPKV0(EmptyScriptPubKey)
        targetOutput = TransactionOutput(coordinator.config.roundAmount, p2wsh)
        // wrong roundId
        challenge = RegisterOutput.calculateChallenge(targetOutput,
                                                      DoubleSha256Digest.empty)
        blind = BlindSchnorrUtil.generateChallenge(coordinator.publicKey,
                                                   aliceDb.nonce,
                                                   tweaks,
                                                   challenge)

        registerInputs = RegisterInputs(Vector(inputRef),
                                        blind,
                                        Some(addr.scriptPubKey))
        blindSig <- coordinator.registerAlice(Sha256Digest.empty,
                                              registerInputs)
        sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                coordinator.publicKey,
                                                aliceDb.nonce,
                                                tweaks,
                                                challenge)
        _ <- coordinator.beginOutputRegistration()
        res <- recoverToSucceededIf[InvalidOutputSignatureException](
          coordinator.verifyAndRegisterBob(RegisterOutput(sig, targetOutput)))
      } yield res
  }

  it must "construct the unsigned tx" in { coordinator =>
    val peerIds =
      0.to(5)
        .map(_ => Sha256Digest(CryptoUtil.randomBytes(32)))
        .toVector

    val aliceDbFs = peerIds.map(peerId =>
      coordinator.getNonce(peerId, dummyQueue(), coordinator.getCurrentRoundId))

    for {
      aliceDbs <- Future.sequence(aliceDbFs)
      updatedAliceDbs = aliceDbs.map { db =>
        val spk = P2WPKHWitnessSPKV0(ECPublicKey.freshPublicKey)
        db.setOutputValues(
          isRemix = false,
          numInputs = 1,
          blindedOutput = ECPrivateKey.freshPrivateKey.fieldElement,
          changeSpkOpt = Some(spk),
          blindOutputSig = ECPrivateKey.freshPrivateKey.fieldElement
        )
      }
      _ <- coordinator.aliceDAO.updateAll(updatedAliceDbs)
      _ <- coordinator.beginOutputRegistration()

      inputDbs = peerIds.map { id =>
        val txid = DoubleSha256Digest(CryptoUtil.randomBytes(32))
        val outPoint =
          TransactionOutPoint(txId = txid, vout = UInt32.zero)
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
        val output = TransactionOutput(coordinator.config.roundAmount, spk)
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
        db.changeSpkOpt match {
          case Some(spk) => tx.outputs.exists(_.scriptPubKey == spk)
          case None      => true
        }
      }
      assert(hasChangeOutputs)

      val coordinatorFee = coordinator.config.coordinatorFee * peerIds.size
      val targetOutput = TransactionOutput(coordinatorFee, addr.scriptPubKey)
      assert(tx.outputs.contains(targetOutput))
    }
  }

  it must "successfully register a psbt signature" in { coordinator =>
    val peerId = Sha256Digest.empty
    val bitcoind = coordinator.bitcoind

    val (queue, source) = Source
      .queue[Message](bufferSize = 10,
                      OverflowStrategy.backpressure,
                      maxConcurrentOffers = 2)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val sink = source.runWith(Sink.seq)

    for {
      aliceDb <- coordinator.getNonce(peerId,
                                      queue,
                                      coordinator.getCurrentRoundId)

      addr <- bitcoind.getNewAddress
      unspent <- bitcoind.listUnspent.map(_.head)
      _ <- coordinator.beginInputRegistration()

      updatedAliceDbs = {
        aliceDb.setOutputValues(
          isRemix = false,
          numInputs = 1,
          blindedOutput = ECPrivateKey.freshPrivateKey.fieldElement,
          changeSpkOpt = Some(addr.scriptPubKey),
          blindOutputSig = ECPrivateKey.freshPrivateKey.fieldElement
        )
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
      _ <- coordinator.beginOutputRegistration()

      outputDb = {
        val raw = P2PKHScriptPubKey(ECPublicKey.freshPublicKey)
        val spk = P2WSHWitnessSPKV0(raw)
        val output = TransactionOutput(coordinator.config.roundAmount, spk)
        val sig = ECPrivateKey.freshPrivateKey.schnorrSign(
          CryptoUtil.sha256(raw.bytes).bytes)

        RegisteredOutputDb(output, sig, coordinator.getCurrentRoundId)
      }
      _ <- coordinator.outputsDAO.create(outputDb)

      addr <- coordinator.bitcoind.getNewAddress
      psbt <- coordinator.constructUnsignedPSBT(addr)

      signed <- bitcoind.walletProcessPSBT(psbt)

      _ <- coordinator.registerPSBTSignatures(peerId, signed.psbt)
      _ = queue.complete()
      msgs <- sink
    } yield assert(msgs.size == 2)
  }

  it must "fail register an invalid psbt signature" in { coordinator =>
    val peerId = Sha256Digest.empty
    val bitcoind = coordinator.bitcoind

    for {
      aliceDb <- coordinator.getNonce(peerId,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)

      addr <- bitcoind.getNewAddress
      unspent <- bitcoind.listUnspent.map(_.head)
      _ <- coordinator.beginInputRegistration()

      updatedAliceDbs = {
        aliceDb.setOutputValues(
          isRemix = false,
          numInputs = 1,
          blindedOutput = ECPrivateKey.freshPrivateKey.fieldElement,
          changeSpkOpt = Some(addr.scriptPubKey),
          blindOutputSig = ECPrivateKey.freshPrivateKey.fieldElement
        )
      }
      _ <- coordinator.aliceDAO.update(updatedAliceDbs)
      _ <- coordinator.beginOutputRegistration()

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
        val output = TransactionOutput(coordinator.config.roundAmount, spk)
        val sig = ECPrivateKey.freshPrivateKey.schnorrSign(
          CryptoUtil.sha256(raw.bytes).bytes)

        RegisteredOutputDb(output, sig, coordinator.getCurrentRoundId)
      }
      _ <- coordinator.outputsDAO.create(outputDb)

      addr <- coordinator.bitcoind.getNewAddress
      psbt <- coordinator.constructUnsignedPSBT(addr)

      // invalid sig
      invalidWit = P2WPKHWitnessV0(ECPublicKey.freshPublicKey)
      signed = psbt.addFinalizedScriptWitnessToInput(EmptyScriptSignature,
                                                     invalidWit,
                                                     0)

      _ <- recoverToSucceededIf[InvalidPSBTSignaturesException](
        coordinator.registerPSBTSignatures(peerId, signed))

      bannedDbs <- coordinator.bannedUtxoDAO.findAll()
    } yield assert(bannedDbs.nonEmpty)
  }

  it must "fail register with a invalid number of inputs" in { coordinator =>
    val peerId = Sha256Digest.empty
    val bitcoind = coordinator.bitcoind

    for {
      aliceDb <- coordinator.getNonce(peerId,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)

      addr <- bitcoind.getNewAddress
      unspent <- bitcoind.listUnspent.map(_.head)
      _ <- coordinator.beginInputRegistration()

      updatedAliceDbs = {
        // wrong number of inputs
        aliceDb.setOutputValues(isRemix = false,
                                numInputs = 2,
                                ECPrivateKey.freshPrivateKey.fieldElement,
                                Some(addr.scriptPubKey),
                                ECPrivateKey.freshPrivateKey.fieldElement)
      }
      _ <- coordinator.aliceDAO.update(updatedAliceDbs)
      _ <- coordinator.beginOutputRegistration()

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
        val output = TransactionOutput(coordinator.config.roundAmount, spk)
        val sig = ECPrivateKey.freshPrivateKey.schnorrSign(
          CryptoUtil.sha256(raw.bytes).bytes)

        RegisteredOutputDb(output, sig, coordinator.getCurrentRoundId)
      }
      _ <- coordinator.outputsDAO.create(outputDb)

      addr <- coordinator.bitcoind.getNewAddress
      psbt <- coordinator.constructUnsignedPSBT(addr)

      signed <- bitcoind.walletProcessPSBT(psbt)

      res <- recoverToSucceededIf[IllegalStateException](
        coordinator.registerPSBTSignatures(peerId, signed.psbt))
    } yield res
  }

  it must "fail register with a different tx" in { coordinator =>
    val peerId = Sha256Digest.empty
    val bitcoind = coordinator.bitcoind

    for {
      aliceDb <- coordinator.getNonce(peerId,
                                      dummyQueue(),
                                      coordinator.getCurrentRoundId)

      addr <- bitcoind.getNewAddress
      unspent <- bitcoind.listUnspent.map(_.head)
      _ <- coordinator.beginInputRegistration()

      updatedAliceDbs = {
        aliceDb.setOutputValues(isRemix = false,
                                numInputs = 1,
                                ECPrivateKey.freshPrivateKey.fieldElement,
                                Some(addr.scriptPubKey),
                                ECPrivateKey.freshPrivateKey.fieldElement)
      }
      _ <- coordinator.aliceDAO.update(updatedAliceDbs)
      _ <- coordinator.beginOutputRegistration()

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
        val output = TransactionOutput(coordinator.config.roundAmount, spk)
        val sig = ECPrivateKey.freshPrivateKey.schnorrSign(
          CryptoUtil.sha256(raw.bytes).bytes)

        RegisteredOutputDb(output, sig, coordinator.getCurrentRoundId)
      }
      _ <- coordinator.outputsDAO.create(outputDb)

      addr <- coordinator.bitcoind.getNewAddress
      _ <- coordinator.constructUnsignedPSBT(addr)
      wrongTx = BaseTransaction(Int32.max,
                                Vector(inputDb.transactionInput),
                                Vector(outputDb.output),
                                UInt32.max)

      _ <- recoverToSucceededIf[DifferentTransactionException](
        coordinator
          .registerPSBTSignatures(peerId, PSBT.fromUnsignedTx(wrongTx)))

      bannedDbs <- coordinator.bannedUtxoDAO.findAll()
    } yield assert(bannedDbs.nonEmpty)
  }
}
