package com.lnvortex.lnd

import com.lnvortex.core._
import com.lnvortex.testkit.LndVortexWalletFixture
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._

import scala.concurrent.Future

class LndVortexWalletTest extends LndVortexWalletFixture {

  it must "correctly sign a psbt" in { wallet =>
    for {
      utxos <- wallet.listCoins
      refs = utxos.map(_.outputReference)
      addr <- wallet.getNewAddress

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
      utxos <- wallet.listCoins
      outRefs = utxos.map(_.outputReference)
      proofFs = outRefs.map(wallet.createInputProof(nonce, _))
      proofs <- Future.sequence(proofFs)
    } yield {
      val inputRefs = outRefs.zip(proofs).map { case (outRef, proof) =>
        InputReference(outRef, proof)
      }
      assert(inputRefs.forall(InputReference.verifyInputProof(_, nonce)))
    }
  }
}
