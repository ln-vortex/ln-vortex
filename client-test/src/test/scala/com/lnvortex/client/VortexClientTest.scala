package com.lnvortex.client

import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindingTweaks
import com.lnvortex.testkit.VortexClientFixture
import io.grpc.StatusRuntimeException
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._

import scala.concurrent.Future

class VortexClientTest extends VortexClientFixture {
  behavior of "VortexClient"

  val dummyAdvertisement: MixDetails = MixDetails(
    version = UInt16.zero,
    roundId = DoubleSha256Digest.empty,
    amount = Satoshis(200000),
    mixFee = Satoshis.zero,
    inputFee = Satoshis.zero,
    outputFee = Satoshis.zero,
    publicKey = ECPublicKey.freshPublicKey.schnorrPublicKey,
    time = UInt64.zero
  )

  val nonce: SchnorrNonce = ECPublicKey.freshPublicKey.schnorrNonce

  val dummyTweaks: BlindingTweaks =
    BlindingTweaks.freshBlindingTweaks(dummyAdvertisement.publicKey, nonce)

  it must "fail to process an unknown version MixAdvertisement" in {
    vortexClient =>
      assertThrows[RuntimeException](
        vortexClient.setRound(dummyAdvertisement.copy(version = UInt16.max)))
  }

  it must "fail to find all output references" in { vortexClient =>
    recoverToSucceededIf[IllegalArgumentException](
      vortexClient.getOutputReferences(Vector(EmptyTransactionOutPoint)))
  }

  it must "correctly sign a psbt" in { vortexClient =>
    val lnd = vortexClient.lndRpcClient

    for {
      utxos <- vortexClient.listCoins()
      refs <- vortexClient.getOutputReferences(utxos.flatMap(_.outPointOpt))
      addr <- lnd.getNewAddress

      inputs = utxos
        .map(_.outPointOpt.get)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))

      output = {
        val amt = utxos.map(_.amount).sum - Satoshis(300)
        TransactionOutput(amt, addr.scriptPubKey)
      }

      tx = BaseTransaction(Int32.two, inputs, Vector(output), UInt32.zero)
      unsigned = PSBT.fromUnsignedTx(tx)
      psbt = refs.zipWithIndex.foldLeft(unsigned) { case (psbt, (utxo, idx)) =>
        psbt.addWitnessUTXOToInput(utxo.output, idx)
      }

      signed <- vortexClient.signPSBT(psbt, refs)
    } yield assert(signed.extractTransactionAndValidate.isSuccess)
  }

  it must "fail to sign a psbt with no channel" in { vortexClient =>
    val lnd = vortexClient.lndRpcClient
    for {
      utxos <- lnd.listUnspent
      refs <- vortexClient.getOutputReferences(utxos.flatMap(_.outPointOpt))
      addrA <- lnd.getNewAddress
      addrB <- lnd.getNewAddress
      change = TransactionOutput(Satoshis(100000), addrA.scriptPubKey)
      mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(inputs = refs,
                                changeOutput = change,
                                chanId = Sha256Digest.empty.bytes,
                                mixOutput = mix,
                                tweaks = dummyTweaks)
      testState = MixOutputRegistered(dummyAdvertisement, nonce, testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change, mix)
      tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
      res <- recoverToSucceededIf[StatusRuntimeException](
        vortexClient.validateAndSignPsbt(PSBT.fromUnsignedTx(tx)))
    } yield res
  }

  it must "fail to sign a psbt with a missing mix output" in { vortexClient =>
    val lnd = vortexClient.lndRpcClient
    for {
      utxos <- lnd.listUnspent
      refs = utxos.map { utxoRes =>
        val output = TransactionOutput(utxoRes.amount, utxoRes.spk)
        OutputReference(utxoRes.outPointOpt.get, output)
      }
      addrA <- lnd.getNewAddress
      addrB <- lnd.getNewAddress
      change = TransactionOutput(Satoshis(100000), addrA.scriptPubKey)
      mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(inputs = refs,
                                changeOutput = change,
                                chanId = Sha256Digest.empty.bytes,
                                mixOutput = mix,
                                tweaks = dummyTweaks)
      testState = MixOutputRegistered(dummyAdvertisement, nonce, testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change)
      tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
      psbt = PSBT.fromUnsignedTx(tx)
      res <- recoverToSucceededIf[RuntimeException](
        vortexClient.validateAndSignPsbt(psbt))
    } yield res
  }

  it must "fail to sign a psbt with a missing change output" in {
    vortexClient =>
      val lnd = vortexClient.lndRpcClient
      for {
        utxos <- lnd.listUnspent
        refs <- vortexClient.getOutputReferences(utxos.flatMap(_.outPointOpt))
        addrA <- lnd.getNewAddress
        addrB <- lnd.getNewAddress
        change = TransactionOutput(Satoshis(100000), addrA.scriptPubKey)
        mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(inputs = refs,
                                  changeOutput = change,
                                  chanId = Sha256Digest.empty.bytes,
                                  mixOutput = mix,
                                  tweaks = dummyTweaks)
        testState = MixOutputRegistered(dummyAdvertisement, nonce, testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(mix)
        tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
        psbt = PSBT.fromUnsignedTx(tx)
        res <- recoverToSucceededIf[RuntimeException](
          vortexClient.validateAndSignPsbt(psbt))
      } yield res
  }

  it must "fail to sign a psbt with a missing input" in { vortexClient =>
    val lnd = vortexClient.lndRpcClient
    for {
      utxos <- lnd.listUnspent
      _ = require(utxos.nonEmpty)
      refs <- vortexClient.getOutputReferences(utxos.flatMap(_.outPointOpt))
      addrA <- lnd.getNewAddress
      addrB <- lnd.getNewAddress
      change = TransactionOutput(Satoshis(100000), addrA.scriptPubKey)
      mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(inputs = refs,
                                changeOutput = change,
                                chanId = Sha256Digest.empty.bytes,
                                mixOutput = mix,
                                tweaks = dummyTweaks)
      testState = MixOutputRegistered(dummyAdvertisement, nonce, testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change, mix)
      tx = BaseTransaction(Int32.two, inputs.tail, outputs, UInt32.zero)
      psbt = PSBT.fromUnsignedTx(tx)
      res <- recoverToSucceededIf[RuntimeException](
        vortexClient.validateAndSignPsbt(psbt))
    } yield res
  }

  it must "correctly create input proofs" in { vortexClient =>
    for {
      utxos <- vortexClient.listCoins()
      outRefs <- vortexClient.getOutputReferences(utxos.map(_.outPointOpt.get))
      proofFs = outRefs.map(vortexClient.createInputProof(nonce, _))
      proofs <- Future.sequence(proofFs)
    } yield {
      val inputRefs = outRefs.zip(proofs).map { case (outRef, proof) =>
        InputReference(outRef, proof)
      }

      assert(inputRefs.forall(InputReference.verifyInputProof(_, nonce)))
    }
  }
}
