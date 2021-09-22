package com.lnvortex.core

import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.tlv.TLVUtil
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._
import scodec.bits._

case class InputReference(
    outputReference: OutputReference,
    inputProof: ScriptWitness)
    extends NetworkElement
    with TLVUtil {

  val output: TransactionOutput = outputReference.output
  val outPoint: TransactionOutPoint = outputReference.outPoint

  override val bytes: ByteVector = {
    u16Prefix(outputReference.bytes) ++ u16Prefix(inputProof.bytes)
  }
}

object InputReference extends Factory[InputReference] {

  def apply(
      outPoint: TransactionOutPoint,
      output: TransactionOutput,
      inputProof: ScriptWitness): InputReference = {
    val outputRef = OutputReference(outPoint, output)
    InputReference(outputRef, inputProof)
  }

  override def fromBytes(bytes: ByteVector): InputReference = {
    val iter = ValueIterator(bytes)
    val outputRef =
      iter.takeU16Prefixed[OutputReference](i => OutputReference(iter.take(i)))

    val inputProof =
      iter.takeU16Prefixed[ScriptWitness](i => ScriptWitness(iter.take(i)))

    InputReference(outputRef, inputProof)
  }

  def constructInputProofTx(
      outPoint: TransactionOutPoint,
      nonce: SchnorrNonce): BaseTransaction = {
    val proofInput =
      TransactionInput(outPoint, EmptyScriptSignature, UInt32.max)

    // include invalid input so tx is not valid and can't be broadcast
    val invalidInput = EmptyTransactionInput

    val nonceHash = CryptoUtil.sha256(nonce.bytes)
    val output =
      TransactionOutput(Satoshis(-1),
                        ScriptPubKey.fromAsmBytes(nonceHash.bytes))

    BaseTransaction(version = Int32.two,
                    inputs = Vector(proofInput, invalidInput),
                    outputs = Vector(output),
                    lockTime = UInt32.zero)
  }

  def constructInputProofTx(
      outputRef: OutputReference,
      nonce: SchnorrNonce): BaseTransaction = {
    constructInputProofTx(outputRef.outPoint, nonce)
  }

  def verifyInputProof(
      inputReference: InputReference,
      nonce: SchnorrNonce): Boolean = {
    val tx = constructInputProofTx(inputReference.outputReference, nonce)

    PSBT
      .fromUnsignedTx(tx)
      .addWitnessUTXOToInput(inputReference.output, 0)
      .addFinalizedScriptWitnessToInput(EmptyScriptSignature,
                                        inputReference.inputProof,
                                        0)
      .verifyFinalizedInput(0)
  }
}
