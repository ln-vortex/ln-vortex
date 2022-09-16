package com.lnvortex.core

import grizzled.slf4j.Logging
import org.bitcoins.commons.serializers.JsonSerializers._
import org.bitcoins.commons.serializers.JsonWriters._
import org.bitcoins.commons.serializers.JsonReaders._
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import play.api.libs.json._
import scodec.bits.ByteVector

sealed trait VortexMessage

/** Messages sent by the client */
sealed abstract class ClientVortexMessage extends VortexMessage

/** Messages sent by the server */
sealed abstract class ServerVortexMessage extends VortexMessage

object VortexMessage extends Logging {

  implicit val vortexMessageReads: Reads[VortexMessage] = Reads { json =>
    json
      .validate[RoundParameters]
      .orElse(json.validate[FeeRateHint])
      .orElse(json.validate[NonceMessage])
      .orElse(json.validate[AskInputs])
      .orElse(json.validate[RegisterInputs])
      .orElse(json.validate[BlindedSig])
      .orElse(json.validate[RegisterOutput])
      .orElse(json.validate[UnsignedPsbtMessage])
      .orElse(json.validate[SignedPsbtMessage])
      .orElse(json.validate[SignedTxMessage])
      .orElse(json.validate[RestartRoundMessage])
      .orElse(json.validate[CancelRegistrationMessage])
  }
}

sealed abstract class ServerAnnouncementMessage extends ServerVortexMessage

object ServerAnnouncementMessage {

  implicit val serverAnnouncementMessageReads: Reads[
    ServerAnnouncementMessage] = Reads { json =>
    json
      .validate[RoundParameters]
      .orElse(json.validate[FeeRateHint])
  }
}

case class RoundParameters(
    version: Int,
    roundId: DoubleSha256Digest,
    amount: CurrencyUnit,
    coordinatorFee: CurrencyUnit,
    publicKey: SchnorrPublicKey,
    time: Long,
    inputType: ScriptType,
    outputType: ScriptType,
    changeType: ScriptType,
    minPeers: Int,
    maxPeers: Int,
    status: String,
    title: Option[String],
    feeRate: SatoshisPerVirtualByte)
    extends ServerAnnouncementMessage {

  def getTargetAmount(isRemix: Boolean, numInputs: Int): CurrencyUnit = {
    if (isRemix) amount
    else {
      // this will cancel out and make this only give us the on-chain fees
      val inputAmt = amount - coordinatorFee
      val Left(onChainFees) = FeeCalculator.calculateChangeOutput(
        roundParams = this,
        isRemix = isRemix,
        numInputs = numInputs,
        numRemixes = 0,
        numNewEntrants = 1,
        inputAmount = inputAmt,
        changeSpkOpt = None)

      amount + coordinatorFee + onChainFees
    }
  }
}

object RoundParameters {

  implicit val RoundParametersReads: Reads[RoundParameters] =
    Json.reads[RoundParameters]

  implicit val RoundParametersWrites: OWrites[RoundParameters] =
    Json.writes[RoundParameters]

}

case class FeeRateHint(feeRate: SatoshisPerVirtualByte)
    extends ServerAnnouncementMessage

object FeeRateHint {

  implicit val FeeRateHintReads: Reads[FeeRateHint] = Json.reads[FeeRateHint]

  implicit val FeeRateHintWrites: OWrites[FeeRateHint] =
    Json.writes[FeeRateHint]

}

case class NonceMessage(schnorrNonce: SchnorrNonce) extends ServerVortexMessage

object NonceMessage {

  implicit lazy val NonceMessageReads: Reads[NonceMessage] =
    Json.reads[NonceMessage]

  implicit lazy val NonceMessageWrites: OWrites[NonceMessage] =
    Json.writes[NonceMessage]
}

case class AskInputs(
    roundId: DoubleSha256Digest,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeOutputFee: CurrencyUnit)
    extends ServerVortexMessage

object AskInputs {

  implicit lazy val AskInputsReads: Reads[AskInputs] = Json.reads[AskInputs]
  implicit lazy val AskInputsWrites: OWrites[AskInputs] = Json.writes[AskInputs]

}

/** First message from client to server
  * @param inputs
  *   inputs Alice is spending in the coin join
  * @param blindedOutput
  *   Response from BlindingTweaks.freshBlindingTweaks &
  *   BlindingTweaks.generateChallenge
  * @param changeSpkOpt
  *   Optional SPK Alice should receive change for
  */
case class RegisterInputs(
    inputs: Vector[InputReference],
    blindedOutput: FieldElement,
    changeSpkOpt: Option[ScriptPubKey])
    extends ClientVortexMessage {

  def isMinimal(target: CurrencyUnit): Boolean = {
    VortexUtils.isMinimalSelection(inputs.map(_.outputReference), target)
  }
}

object RegisterInputs {

  implicit lazy val RegisterInputsReads: Reads[RegisterInputs] =
    Json.reads[RegisterInputs]

  implicit lazy val RegisterInputsWrites: OWrites[RegisterInputs] =
    Json.writes[RegisterInputs]

}

/** Response from coordinator to Alice's first message
  * @param blindOutputSig
  *   Response from BlindingTweaks.generateBlindSig
  */
case class BlindedSig(blindOutputSig: FieldElement) extends ServerVortexMessage

object BlindedSig {

  implicit lazy val BlindedSigReads: Reads[BlindedSig] = Json.reads[BlindedSig]

  implicit lazy val BlindedSigWrites: OWrites[BlindedSig] =
    Json.writes[BlindedSig]
}

/** @param sig
  *   Response from BlindingTweaks.unblindSignature
  * @param output
  *   Output they are registering
  */
case class RegisterOutput(
    sig: SchnorrDigitalSignature,
    output: TransactionOutput)
    extends ClientVortexMessage {

  def verifySig(
      publicKey: SchnorrPublicKey,
      roundId: DoubleSha256Digest): Boolean = {
    val challenge = RegisterOutput.calculateChallenge(output, roundId)
    publicKey.verify(challenge, sig)
  }
}

object RegisterOutput {

  implicit lazy val RegisterOutputReads: Reads[RegisterOutput] =
    Json.reads[RegisterOutput]

  implicit lazy val RegisterOutputWrites: OWrites[RegisterOutput] =
    Json.writes[RegisterOutput]

  def calculateChallenge(
      output: TransactionOutput,
      roundId: DoubleSha256Digest): ByteVector = {
    CryptoUtil.sha256(output.bytes ++ roundId.bytes).bytes
  }
}

/** @param psbt
  *   Unsigned PSBT of the transaction
  */
case class UnsignedPsbtMessage(psbt: PSBT) extends ServerVortexMessage

object UnsignedPsbtMessage {

  implicit lazy val UnsignedPsbtMessageReads: Reads[UnsignedPsbtMessage] =
    Json.reads[UnsignedPsbtMessage]

  implicit lazy val UnsignedPsbtMessageWrites: OWrites[UnsignedPsbtMessage] =
    Json.writes[UnsignedPsbtMessage]

}

case class SignedPsbtMessage(signedPsbt: PSBT) extends ClientVortexMessage

object SignedPsbtMessage {

  implicit lazy val SignedPsbtMessageReads: Reads[SignedPsbtMessage] =
    Json.reads[SignedPsbtMessage]

  implicit lazy val SignedPsbtMessageWrites: OWrites[SignedPsbtMessage] =
    Json.writes[SignedPsbtMessage]

}

/** @param transaction
  *   Full signed transaction
  */
case class SignedTxMessage(transaction: Transaction) extends ServerVortexMessage

object SignedTxMessage {

  implicit lazy val SignedTxMessageReads: Reads[SignedTxMessage] =
    Json.reads[SignedTxMessage]

  implicit lazy val SignedTxMessageWrites: OWrites[SignedTxMessage] =
    Json.writes[SignedTxMessage]
}

case class RestartRoundMessage(
    roundParams: RoundParameters,
    nonceMessage: NonceMessage)
    extends ServerVortexMessage

object RestartRoundMessage {

  import NonceMessage._

  implicit lazy val RestartRoundMessageReads: Reads[RestartRoundMessage] =
    Json.reads[RestartRoundMessage]

  implicit lazy val RestartRoundMessageWrites: OWrites[RestartRoundMessage] =
    Json.writes[RestartRoundMessage]
}

case class CancelRegistrationMessage(
    nonce: SchnorrNonce,
    roundId: DoubleSha256Digest)
    extends ClientVortexMessage

object CancelRegistrationMessage {

  implicit lazy val CancelRegistrationMessageReads: Reads[
    CancelRegistrationMessage] =
    Json.reads[CancelRegistrationMessage]

  implicit lazy val CancelRegistrationMessageWrites: OWrites[
    CancelRegistrationMessage] =
    Json.writes[CancelRegistrationMessage]
}
