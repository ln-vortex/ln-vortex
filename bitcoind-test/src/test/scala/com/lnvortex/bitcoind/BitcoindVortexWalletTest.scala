package com.lnvortex.bitcoind

import com.lnvortex.core._
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.concurrent.Future

class BitcoindVortexWalletTest extends BitcoinSFixture with CachedBitcoindV23 {

  override type FixtureParam = BitcoindVortexWallet

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[BitcoindVortexWallet](
      () => cachedBitcoindWithFundsF.map(BitcoindVortexWallet(_, None)),
      bitcoind => bitcoind.stop()
    )(test)
  }

  // todo add negative tests

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
