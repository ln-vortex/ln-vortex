package com.lnvortex.lnd

import com.lnvortex.core._
import com.lnvortex.testkit.LndVortexWalletFixture
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

class LndVortexWalletTest extends LndVortexWalletFixture {

  it must "get network" in { wallet =>
    assert(wallet.network == RegTest)
  }

  it must "correctly sign a psbt with segwitV0 inputs" in { wallet =>
    for {
      utxos <- wallet
        .listCoins()
        .map(_.filter(_.address.scriptPubKey.isInstanceOf[P2WPKHWitnessSPKV0]))
      refs = utxos.map(_.outputReference)
      addr <- wallet.getNewAddress(ScriptType.WITNESS_V1_TAPROOT)

      inputs = utxos
        .map(_.outPoint)
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

      signed <- wallet.signPSBT(psbt, refs)
      _ <- wallet.broadcastTransaction(signed.extractTransaction)
    } yield signed.extractTransactionAndValidate match {
      case Failure(exception) => fail(exception)
      case Success(_)         => succeed
    }
  }

  it must "correctly sign a psbt with taproot inputs" in { wallet =>
    for {
      utxos <- wallet
        .listCoins()
        .map(_.filter(_.address.scriptPubKey.isInstanceOf[TaprootScriptPubKey]))
      refs = utxos.map(_.outputReference)
      addr <- wallet.getNewAddress(ScriptType.WITNESS_V1_TAPROOT)

      inputs = utxos
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.zero))

      output = {
        val amt = utxos.map(_.amount).sum - Satoshis(300)
        TransactionOutput(amt, addr.scriptPubKey)
      }

      tx = BaseTransaction(Int32.two, inputs, Vector(output), UInt32.zero)
      unsigned = PSBT.fromUnsignedTx(tx)
      psbt = inputs.zipWithIndex.foldLeft(unsigned) {
        case (psbt, (input, idx)) =>
          val prevOut =
            utxos.find(_.outPoint == input.previousOutput).get.output
          psbt.addWitnessUTXOToInput(prevOut, idx)
      }

      signed <- wallet.signPSBT(psbt, refs)
      _ <- wallet.broadcastTransaction(signed.extractTransaction)
    } yield signed.extractTransactionAndValidate match {
      case Failure(exception) => fail(exception)
      case Success(_)         => succeed
    }
  }

  it must "correctly sign a psbt of mixed input types" in { wallet =>
    for {
      utxos <- wallet.listCoins()
      refs = utxos.map(_.outputReference)
      addr <- wallet.getNewAddress(ScriptType.WITNESS_V1_TAPROOT)

      inputs = utxos
        .map(_.outPoint)
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

      signed <- wallet.signPSBT(psbt, refs)
    } yield signed.extractTransactionAndValidate match {
      case Failure(exception) => fail(exception)
      case Success(_)         => succeed
    }
  }

  it must "correctly create input proofs" in { wallet =>
    val nonce: SchnorrNonce = ECPublicKey.freshPublicKey.schnorrNonce

    for {
      utxos <- wallet.listCoins()
      outRefs = utxos.map(_.outputReference)
      proofFs = outRefs.map(wallet.createInputProof(nonce, _, 3600.seconds))
      proofs <- Future.sequence(proofFs)

      _ <- wallet.releaseCoins(outRefs)
    } yield {
      val inputRefs = outRefs.zip(proofs).map { case (outRef, proof) =>
        InputReference(outRef, proof)
      }
      assert(inputRefs.forall(InputReference.verifyInputProof(_, nonce)))
    }
  }
}
