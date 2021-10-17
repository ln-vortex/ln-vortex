package com.lnvortex.clightning

import com.lnvortex.core._
import com.lnvortex.testkit.CLightningCoinJoinWalletFixture
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.{Int32, UInt32}
import org.bitcoins.core.protocol.script.EmptyScriptSignature
import org.bitcoins.core.protocol.transaction.{
  BaseTransaction,
  TransactionInput,
  TransactionOutput
}
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._

import scala.concurrent.Future

class CLightningCoinJoinWalletTest extends CLightningCoinJoinWalletFixture {
  behavior of "CLightningCoinJoinWallet"

  it must "correctly sign a psbt" in { coinjoinWallet =>
    for {
      utxos <- coinjoinWallet.listCoins
      refs = utxos.map(_.outputReference)
      addr <- coinjoinWallet.getNewAddress

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

      signed <- coinjoinWallet.signPSBT(psbt, refs)
    } yield assert(signed.extractTransactionAndValidate.isSuccess)
  }

  it must "correctly create input proofs" in { coinjoinWallet =>
    val nonce: SchnorrNonce = ECPublicKey.freshPublicKey.schnorrNonce

    for {
      utxos <- coinjoinWallet.listCoins
      outRefs = utxos.map(_.outputReference)
      proofFs = outRefs.map(coinjoinWallet.createInputProof(nonce, _))
      proofs <- Future.sequence(proofFs)
    } yield {
      val inputRefs = outRefs.zip(proofs).map { case (outRef, proof) =>
        InputReference(outRef, proof)
      }
      assert(inputRefs.forall(InputReference.verifyInputProof(_, nonce)))
    }
  }
}
