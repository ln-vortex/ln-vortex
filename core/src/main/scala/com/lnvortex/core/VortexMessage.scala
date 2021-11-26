package com.lnvortex.core

import grizzled.slf4j.Logging
import org.bitcoins.core.config.{BitcoinNetwork, Networks}
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.tlv.TLV._
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._
import scodec.bits.ByteVector

sealed trait VortexMessage extends NetworkElement with TLVUtil {
  def tpe: BigSizeUInt

  def length: BigSizeUInt = {
    BigSizeUInt.calcFor(value)
  }

  def value: ByteVector

  override lazy val bytes: ByteVector = tpe.bytes ++ length.bytes ++ value

  lazy val typeName: String = VortexMessage.getTypeName(tpe)
}

/** Messages sent by the client */
sealed abstract class ClientVortexMessage extends VortexMessage

/** Messages sent by the server */
sealed abstract class ServerVortexMessage extends VortexMessage

object VortexMessage extends Factory[VortexMessage] with Logging {

  val allFactories: Vector[VortexMessageFactory[VortexMessage]] =
    Vector(
      AskMixDetails,
      MixDetails,
      AskNonce,
      NonceMessage,
      AskInputs,
      RegisterInputs,
      BlindedSig,
      RegisterMixOutput,
      UnsignedPsbtMessage,
      SignedPsbtMessage,
      SignedTxMessage,
      RestartRoundMessage,
      CancelRegistrationMessage
    )

  lazy val knownTypes: Vector[BigSizeUInt] = allFactories.map(_.tpe)

  def getTypeName(tpe: BigSizeUInt): String = {
    allFactories
      .find(_.tpe == tpe)
      .map(_.typeName)
      .getOrElse("Unknown TLV type")
  }

  override def fromBytes(bytes: ByteVector): VortexMessage = {
    val DecodeTLVResult(tpe, _, value) = TLV.decodeTLV(bytes)

    allFactories.find(_.tpe == tpe) match {
      case Some(fac) => fac.fromTLVValue(value)
      case None =>
        logger.warn(
          s"Unknown $typeName type got $tpe (${TLV.getTypeName(tpe)})")

        UnknownVortexMessage(tpe, value)
    }
  }
}

sealed trait VortexMessageFactory[+T <: VortexMessage] extends Factory[T] {
  def tpe: BigSizeUInt

  def typeName: String

  def fromTLVValue(value: ByteVector): T

  override def fromBytes(bytes: ByteVector): T = {
    val DecodeTLVResult(tpe, _, value) = TLV.decodeTLV(bytes)

    require(
      tpe == this.tpe,
      s"Invalid type $tpe (${TLV.getTypeName(tpe)}) when expecting ${this.tpe}")

    fromTLVValue(value)
  }
}

case class UnknownVortexMessage(tpe: BigSizeUInt, value: ByteVector)
    extends VortexMessage {
  require(!VortexMessage.knownTypes.contains(tpe), s"Type $tpe is known")
}

object UnknownVortexMessage extends Factory[UnknownVortexMessage] {

  override def fromBytes(bytes: ByteVector): UnknownVortexMessage = {
    val DecodeTLVResult(tpe, _, value) = TLV.decodeTLV(bytes)

    UnknownVortexMessage(tpe, value)
  }
}

case class AskMixDetails(network: BitcoinNetwork) extends ClientVortexMessage {
  override val tpe: BigSizeUInt = AskMixDetails.tpe

  override val value: ByteVector = {
    network.chainParams.genesisBlock.blockHeader.hashBE.bytes
  }
}

object AskMixDetails extends VortexMessageFactory[AskMixDetails] {
  override val tpe: BigSizeUInt = BigSizeUInt(42001)

  override val typeName: String = "AskMixDetails"

  override def fromTLVValue(value: ByteVector): AskMixDetails = {
    val network = Networks.fromChainHash(DoubleSha256DigestBE(value)) match {
      case network: BitcoinNetwork => network
    }

    AskMixDetails(network)
  }
}

case class MixDetails(
    version: UInt16,
    roundId: DoubleSha256Digest,
    inputRegistrationType: InputRegistrationType,
    amount: CurrencyUnit,
    mixFee: CurrencyUnit,
    publicKey: SchnorrPublicKey,
    time: UInt64)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = MixDetails.tpe

  override val value: ByteVector = {
    version.bytes ++
      roundId.bytes ++
      inputRegistrationType.bytes ++
      amount.satoshis.toUInt64.bytes ++
      mixFee.satoshis.toUInt64.bytes ++
      publicKey.bytes ++
      time.bytes
  }
}

object MixDetails extends VortexMessageFactory[MixDetails] {
  override val tpe: BigSizeUInt = BigSizeUInt(42003)

  override val typeName: String = "MixDetails"

  override def fromTLVValue(value: ByteVector): MixDetails = {
    val iter = ValueIterator(value)

    val version = iter.takeU16()
    val roundId = DoubleSha256Digest(iter.take(32))
    val regType = InputRegistrationType.fromUInt16(iter.takeU16())
    val amount = iter.takeSats()
    val mixFee = iter.takeSats()
    val publicKey = SchnorrPublicKey(iter.take(32))
    val time = iter.takeU64()

    MixDetails(version = version,
               roundId = roundId,
               inputRegistrationType = regType,
               amount = amount,
               mixFee = mixFee,
               publicKey = publicKey,
               time = time)
  }
}

case class AskNonce(roundId: DoubleSha256Digest) extends ClientVortexMessage {
  override val tpe: BigSizeUInt = AskNonce.tpe

  override val value: ByteVector = {
    roundId.bytes
  }
}

object AskNonce extends VortexMessageFactory[AskNonce] {
  override val tpe: BigSizeUInt = BigSizeUInt(42005)

  override val typeName: String = "AskNonce"

  override def fromTLVValue(value: ByteVector): AskNonce = {
    val iter = ValueIterator(value)
    val roundId = DoubleSha256Digest(iter.take(32))

    AskNonce(roundId)
  }
}

case class NonceMessage(schnorrNonce: SchnorrNonce)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = NonceMessage.tpe

  override val value: ByteVector = {
    schnorrNonce.bytes
  }
}

object NonceMessage extends VortexMessageFactory[NonceMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(42007)

  override val typeName: String = "NonceMessage"

  override def fromTLVValue(value: ByteVector): NonceMessage = {
    val iter = ValueIterator(value)
    val nonce = SchnorrNonce(iter.take(32))

    NonceMessage(nonce)
  }
}

case class AskInputs(
    roundId: DoubleSha256Digest,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = AskInputs.tpe

  override val value: ByteVector = {
    roundId.bytes ++ inputFee.satoshis.toUInt64.bytes ++ outputFee.satoshis.toUInt64.bytes
  }
}

object AskInputs extends VortexMessageFactory[AskInputs] {
  override val tpe: BigSizeUInt = BigSizeUInt(42009)

  override val typeName: String = "AskInputs"

  override def fromTLVValue(value: ByteVector): AskInputs = {
    val iter = ValueIterator(value)
    val roundId = DoubleSha256Digest(iter.take(32))
    val inputFee = iter.takeSats()
    val outputFee = iter.takeSats()

    AskInputs(roundId, inputFee, outputFee)
  }
}

/** First message from client to server
  * @param inputs inputs Alice is spending in the coin join
  * @param blindedOutput Response from BlindingTweaks.freshBlindingTweaks & BlindingTweaks.generateChallenge
  * @param changeSpkOpt Optional SPK Alice should receive change for
  */
case class RegisterInputs(
    inputs: Vector[InputReference],
    blindedOutput: FieldElement,
    changeSpkOpt: Option[ScriptPubKey])
    extends ClientVortexMessage {
  override val tpe: BigSizeUInt = RegisterInputs.tpe

  override val value: ByteVector = {

    val changeSpkBytes = changeSpkOpt match {
      case Some(changeSpk) => u16Prefix(changeSpk.asmBytes)
      case None            => UInt16.zero.bytes
    }

    u16PrefixedList[InputReference](
      inputs,
      (t: InputReference) => u16Prefix(t.bytes)) ++
      blindedOutput.bytes ++
      changeSpkBytes
  }
}

object RegisterInputs extends VortexMessageFactory[RegisterInputs] {
  override val tpe: BigSizeUInt = BigSizeUInt(42011)

  override val typeName: String = "RegisterInputs"

  override def fromTLVValue(value: ByteVector): RegisterInputs = {
    val iter = ValueIterator(value)

    val inputs = iter.takeU16PrefixedList[InputReference](() =>
      iter.takeU16Prefixed[InputReference](len =>
        InputReference(iter.take(len))))

    val blindedOutput = FieldElement(iter.take(32))

    val changeSpkLen = iter.takeU16()

    val changeSpkOpt = if (changeSpkLen != UInt16.zero) {
      Some(ScriptPubKey.fromAsmBytes(iter.take(changeSpkLen.toInt)))
    } else None

    RegisterInputs(inputs, blindedOutput, changeSpkOpt)
  }
}

/** Response from mixer to Alice's first message
  * @param blindOutputSig Response from BlindingTweaks.generateBlindSig
  */
case class BlindedSig(blindOutputSig: FieldElement)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = BlindedSig.tpe

  override val value: ByteVector = blindOutputSig.bytes
}

object BlindedSig extends VortexMessageFactory[BlindedSig] {
  override val tpe: BigSizeUInt = BigSizeUInt(42013)

  override val typeName: String = "BlindedSig"

  override def fromTLVValue(value: ByteVector): BlindedSig = {
    val blindOutputSig = FieldElement(value)
    BlindedSig(blindOutputSig)
  }
}

/** @param sig Response from BlindingTweaks.unblindSignature
  * @param output Output they are registering
  */
case class RegisterMixOutput(
    sig: SchnorrDigitalSignature,
    output: TransactionOutput)
    extends ClientVortexMessage {
  override val tpe: BigSizeUInt = RegisterMixOutput.tpe

  override val value: ByteVector = sig.bytes ++ u16Prefix(output.bytes)

  def verifySig(
      publicKey: SchnorrPublicKey,
      roundId: DoubleSha256Digest): Boolean = {
    val challenge = RegisterMixOutput.calculateChallenge(output, roundId)
    publicKey.verify(challenge, sig)
  }
}

object RegisterMixOutput extends VortexMessageFactory[RegisterMixOutput] {
  override val tpe: BigSizeUInt = BigSizeUInt(42015)

  override val typeName: String = "RegisterMixOutput"

  override def fromTLVValue(value: ByteVector): RegisterMixOutput = {
    val iter = ValueIterator(value)

    val sig = iter.take(SchnorrDigitalSignature, 64)
    val output = iter.takeU16Prefixed[TransactionOutput](len =>
      TransactionOutput(iter.take(len)))

    RegisterMixOutput(sig, output)
  }

  def calculateChallenge(
      output: TransactionOutput,
      roundId: DoubleSha256Digest): ByteVector = {
    CryptoUtil.sha256(output.bytes ++ roundId.bytes).bytes
  }
}

/** @param psbt Unsigned PSBT of the transaction
  */
case class UnsignedPsbtMessage(psbt: PSBT) extends ServerVortexMessage {
  override val tpe: BigSizeUInt = UnsignedPsbtMessage.tpe

  override lazy val value: ByteVector = psbt.bytes
}

object UnsignedPsbtMessage extends VortexMessageFactory[UnsignedPsbtMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(42017)

  override val typeName: String = "UnsignedPsbtMessage"

  override def fromTLVValue(value: ByteVector): UnsignedPsbtMessage = {
    val psbt = PSBT(value)

    UnsignedPsbtMessage(psbt)
  }
}

/** @param psbt Signed PSBT
  */
case class SignedPsbtMessage(psbt: PSBT) extends ClientVortexMessage {
  override val tpe: BigSizeUInt = SignedPsbtMessage.tpe

  override lazy val value: ByteVector = psbt.bytes
}

object SignedPsbtMessage extends VortexMessageFactory[SignedPsbtMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(42019)

  override val typeName: String = "SignedPsbtMessage"

  override def fromTLVValue(value: ByteVector): SignedPsbtMessage = {
    val psbt = PSBT(value)

    SignedPsbtMessage(psbt)
  }
}

/** @param transaction Full signed transaction
  */
case class SignedTxMessage(transaction: Transaction)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = SignedTxMessage.tpe

  override lazy val value: ByteVector = transaction.bytes
}

object SignedTxMessage extends VortexMessageFactory[SignedTxMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(42021)

  override val typeName: String = "SignedTxMessage"

  override def fromTLVValue(value: ByteVector): SignedTxMessage = {
    val transaction = Transaction(value)

    SignedTxMessage(transaction)
  }
}

case class RestartRoundMessage(
    mixDetails: MixDetails,
    nonceMessage: NonceMessage)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = RestartRoundMessage.tpe

  override lazy val value: ByteVector = mixDetails.bytes ++ nonceMessage.bytes
}

object RestartRoundMessage extends VortexMessageFactory[RestartRoundMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(42023)

  override val typeName: String = "RestartRoundMessage"

  override def fromTLVValue(value: ByteVector): RestartRoundMessage = {
    val iter = ValueIterator(value)

    val mixDetails = iter.take(MixDetails)
    val nonceMessage = iter.take(NonceMessage)

    RestartRoundMessage(mixDetails, nonceMessage)
  }
}

case class CancelRegistrationMessage(
    nonce: SchnorrNonce,
    roundId: DoubleSha256Digest)
    extends ClientVortexMessage {
  override val tpe: BigSizeUInt = RestartRoundMessage.tpe

  override lazy val value: ByteVector = nonce.bytes ++ roundId.bytes
}

object CancelRegistrationMessage
    extends VortexMessageFactory[CancelRegistrationMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(42025)

  override val typeName: String = "CancelRegistrationMessage"

  override def fromTLVValue(value: ByteVector): CancelRegistrationMessage = {
    val iter = ValueIterator(value)

    val nonce = iter.take(SchnorrNonce)
    val roundId = iter.take(DoubleSha256Digest)

    CancelRegistrationMessage(nonce, roundId)
  }
}
