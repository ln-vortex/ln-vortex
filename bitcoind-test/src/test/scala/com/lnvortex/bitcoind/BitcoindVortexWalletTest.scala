package com.lnvortex.bitcoind

import com.lnvortex.core._
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class BitcoindVortexWalletTest extends BitcoinSFixture with CachedBitcoindV23 {

  override type FixtureParam = BitcoindVortexWallet

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[BitcoindVortexWallet](
      () => cachedBitcoindWithFundsF.map(BitcoindVortexWallet(_, None)),
      bitcoind => bitcoind.stop()
    )(test)
  }

  it must "get block height" in { bitcoind =>
    for {
      height <- bitcoind.getBlockHeight()
    } yield assert(height > 0)
  }

  it must "get addresses" in { wallet =>
    for {
      p2wpkh <- wallet.getNewAddress(ScriptType.WITNESS_V0_KEYHASH)
      p2tr <- wallet.getNewAddress(ScriptType.WITNESS_V1_TAPROOT)
      nested <- wallet.getNewAddress(ScriptType.SCRIPTHASH)
      p2pkh <- wallet.getNewAddress(ScriptType.PUBKEYHASH)
      _ = assertThrows[IllegalArgumentException](
        wallet.getNewAddress(ScriptType.CLTV))
    } yield {
      assert(p2wpkh.value.startsWith("bcrt1q"))
      assert(p2tr.value.startsWith("bcrt1p"))
      assert(nested.value.startsWith("2"))
      assert(p2pkh.value.startsWith("n") || p2pkh.value.startsWith("m"))
    }
  }

  it must "get change addresses" in { wallet =>
    for {
      p2wpkh <- wallet.getChangeAddress(ScriptType.WITNESS_V0_KEYHASH)
      p2tr <- wallet.getChangeAddress(ScriptType.WITNESS_V1_TAPROOT)
    } yield {
      assert(p2wpkh.value.startsWith("bcrt1q"))
      assert(p2tr.value.startsWith("bcrt1p"))
    }
  }

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

  it must "fail lightning functions" in { wallet =>
    val nodeId = NodeId(ECPublicKey.freshPublicKey)
    for {
      _ <- recoverToSucceededIf[UnsupportedOperationException](
        wallet.listChannels())
      _ <- recoverToSucceededIf[UnsupportedOperationException](
        wallet.cancelPendingChannel(ByteVector.empty))
      _ <- recoverToSucceededIf[UnsupportedOperationException](
        wallet.cancelChannel(EmptyTransactionOutPoint, nodeId))
      _ <- recoverToSucceededIf[UnsupportedOperationException](
        wallet.connect(nodeId,
                       InetSocketAddress.createUnresolved("localhost", 9735)))
      _ <- recoverToSucceededIf[UnsupportedOperationException](
        wallet.isConnected(nodeId))
      _ <- recoverToSucceededIf[UnsupportedOperationException](
        wallet
          .initChannelOpen(nodeId, Satoshis.zero, privateChannel = false))
      _ <- recoverToSucceededIf[UnsupportedOperationException](
        wallet
          .completeChannelOpen(ByteVector.empty, PSBT.empty))
    } yield succeed
  }
}
