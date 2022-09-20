package com.lnvortex.clightning

import com.lnvortex.core._
import com.lnvortex.testkit.CLightningVortexWalletFixture
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.{Int32, UInt32}
import org.bitcoins.core.protocol.script.EmptyScriptSignature
import org.bitcoins.core.protocol.transaction.{
  BaseTransaction,
  TransactionInput,
  TransactionOutput
}
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class CLightningVortexWalletTest extends CLightningVortexWalletFixture {

  it must "correctly sign a psbt" in { wallet =>
    for {
      utxos <- wallet.listCoins()
      refs = utxos.map(_.outputReference)
      addr <- wallet.getNewAddress(ScriptType.WITNESS_V0_KEYHASH)

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
    } yield assert(signed.extractTransactionAndValidate.isSuccess)
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
