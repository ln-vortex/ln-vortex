package com.lnvortex.core

import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.tlv.TLVUtil
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.script.interpreter.ScriptInterpreter
import org.bitcoins.core.script.util.PreviousOutputMap
import org.bitcoins.crypto._
import org.bitcoins.commons.serializers.JsonSerializers._
import org.bitcoins.commons.serializers.JsonWriters._
import org.bitcoins.commons.serializers.JsonReaders._
import play.api.libs.json._
import scodec.bits._

case class InputReference(
    outputReference: OutputReference,
    inputProof: ScriptWitness)
    extends NetworkElement
    with TLVUtil {

  val output: TransactionOutput = outputReference.output
  val outPoint: TransactionOutPoint = outputReference.outPoint

  override def bytes: ByteVector = {
    u16Prefix(outputReference.bytes) ++ u16Prefix(inputProof.bytes)
  }
}

object InputReference {

  def constructInputProofTx(
      outPoint: TransactionOutPoint,
      nonce: SchnorrNonce): BaseTransaction = {
    val proofInput =
      TransactionInput(outPoint, EmptyScriptSignature, UInt32.max)

    // include invalid input so tx is not valid and can't be broadcast
    val invalidInput = EmptyTransactionInput

    val nonceHash = CryptoUtil.sha256(nonce.bytes)
    val output =
      TransactionOutput(Satoshis.zero, P2WSHWitnessSPKV0.fromHash(nonceHash))

    BaseTransaction(version = Int32.two,
                    inputs = Vector(proofInput, invalidInput),
                    outputs = Vector(output),
                    lockTime = UInt32.max)
  }

  def constructInputProofTx(
      outputRef: OutputReference,
      nonce: SchnorrNonce): BaseTransaction = {
    constructInputProofTx(outputRef.outPoint, nonce)
  }

  def verifyInputProof(
      inputReference: InputReference,
      nonce: SchnorrNonce): Boolean = {
    val tx = constructInputProofTx(inputReference.outPoint, nonce)

    val wtx = WitnessTransaction
      .toWitnessTx(tx)
      .updateWitness(0, inputReference.inputProof)

    val outputMap = Map(tx.inputs.head.previousOutput -> inputReference.output,
                        tx.inputs.last.previousOutput -> EmptyTransactionOutput)

    ScriptInterpreter.verifyInputScript(transaction = wtx,
                                        inputIndex = 0,
                                        outputMap =
                                          PreviousOutputMap(outputMap),
                                        prevOut = inputReference.output)
  }

  implicit lazy val InputReferenceReads: Reads[InputReference] =
    Json.reads[InputReference]

  implicit lazy val InputReferenceWrites: OWrites[InputReference] =
    Json.writes[InputReference]
}
