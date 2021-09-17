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

sealed abstract class ClientVortexMessage extends VortexMessage
sealed abstract class ServerVortexMessage extends VortexMessage

object VortexMessage extends Factory[VortexMessage] with Logging {

  val allFactories: Vector[VortexMessageFactory[VortexMessage]] =
    Vector(AskMixAdvertisement,
           MixAdvertisement,
           AliceInit,
           AliceInitResponse,
           BobMessage,
           UnsignedPsbtMessage,
           SignedPsbtMessage,
           SignedTxMessage)

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
        logger.info(
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

case class AskMixAdvertisement(network: BitcoinNetwork)
    extends ClientVortexMessage {
  override val tpe: BigSizeUInt = AskMixAdvertisement.tpe

  override val value: ByteVector = {
    network.chainParams.genesisBlock.blockHeader.hashBE.bytes
  }
}

object AskMixAdvertisement extends VortexMessageFactory[AskMixAdvertisement] {
  override val tpe: BigSizeUInt = BigSizeUInt(696965L)

  override val typeName: String = "AskMixAdvertisement"

  override def fromTLVValue(value: ByteVector): AskMixAdvertisement = {
    val network = Networks.fromChainHash(DoubleSha256DigestBE(value)) match {
      case network: BitcoinNetwork => network
    }

    AskMixAdvertisement(network)
  }
}

case class MixAdvertisement(
    version: UInt16,
    amount: CurrencyUnit,
    mixFee: CurrencyUnit,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    publicKey: SchnorrPublicKey,
    nonce: SchnorrNonce,
    time: UInt64)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = MixAdvertisement.tpe

  override val value: ByteVector = {
    version.bytes ++
      amount.satoshis.toUInt64.bytes ++
      mixFee.satoshis.toUInt64.bytes ++
      inputFee.satoshis.toUInt64.bytes ++
      outputFee.satoshis.toUInt64.bytes ++
      publicKey.bytes ++
      nonce.bytes ++
      time.bytes
  }
}

object MixAdvertisement extends VortexMessageFactory[MixAdvertisement] {
  override val tpe: BigSizeUInt = BigSizeUInt(696967L)

  override val typeName: String = "MixAdvertisement"

  override def fromTLVValue(value: ByteVector): MixAdvertisement = {
    val iter = ValueIterator(value)

    val version = iter.takeU16()
    val amount = iter.takeSats()
    val mixFee = iter.takeSats()
    val inputFee = iter.takeSats()
    val outputFee = iter.takeSats()
    val publicKey = SchnorrPublicKey(iter.take(32))
    val nonce = SchnorrNonce(iter.take(32))
    val time = iter.takeU64()

    MixAdvertisement(version = version,
                     amount = amount,
                     mixFee = mixFee,
                     inputFee = inputFee,
                     outputFee = outputFee,
                     publicKey = publicKey,
                     nonce = nonce,
                     time = time)
  }
}

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

  override def fromBytes(bytes: ByteVector): InputReference = {
    val iter = ValueIterator(bytes)
    val outputRef =
      iter.takeU16Prefixed[OutputReference](i => OutputReference(iter.take(i)))

    val inputProof =
      iter.takeU16Prefixed[ScriptWitness](i => ScriptWitness(iter.take(i)))

    InputReference(outputRef, inputProof)
  }
}

/** First message from client to server
  * @param inputs inputs Alice is spending in the coin join
  * @param blindedOutput Response from BlindingTweaks.freshBlindingTweaks & BlindingTweaks.generateChallenge
  * @param changeOutput output Alice should receive
  */
case class AliceInit(
    inputs: Vector[InputReference],
    blindedOutput: FieldElement,
    changeOutput: TransactionOutput)
    extends ClientVortexMessage {
  override val tpe: BigSizeUInt = AliceInit.tpe

  override val value: ByteVector = {
    u16PrefixedList[InputReference](
      inputs,
      (t: InputReference) => u16Prefix(t.bytes)) ++
      blindedOutput.bytes ++
      u16Prefix(changeOutput.bytes)
  }
}

object AliceInit extends VortexMessageFactory[AliceInit] {
  override val tpe: BigSizeUInt = BigSizeUInt(696969L)

  override val typeName: String = "AliceInit"

  override def fromTLVValue(value: ByteVector): AliceInit = {
    val iter = ValueIterator(value)

    val inputs = iter.takeU16PrefixedList[InputReference](() =>
      iter.takeU16Prefixed[InputReference](len =>
        InputReference(iter.take(len))))

    val blindedOutput = FieldElement(iter.take(32))

    val output = iter.takeU16Prefixed[TransactionOutput](len =>
      TransactionOutput(iter.take(len)))

    AliceInit(inputs, blindedOutput, output)
  }
}

/** Response from mixer to Alice's first message
  * @param blindOutputSig Response from BlindingTweaks.generateBlindSig
  */
case class AliceInitResponse(blindOutputSig: FieldElement)
    extends ServerVortexMessage {
  override val tpe: BigSizeUInt = AliceInitResponse.tpe

  override val value: ByteVector = blindOutputSig.bytes
}

object AliceInitResponse extends VortexMessageFactory[AliceInitResponse] {
  override val tpe: BigSizeUInt = BigSizeUInt(696971L)

  override val typeName: String = "AliceInitResponse"

  override def fromTLVValue(value: ByteVector): AliceInitResponse = {
    val blindOutputSig = FieldElement(value)
    AliceInitResponse(blindOutputSig)
  }
}

/** @param sig Response from BlindingTweaks.unblindSignature
  * @param output Output they are registering
  */
case class BobMessage(sig: SchnorrDigitalSignature, output: TransactionOutput)
    extends ClientVortexMessage {
  override val tpe: BigSizeUInt = BobMessage.tpe

  override val value: ByteVector = sig.bytes ++ u16Prefix(output.bytes)

  def verifySigAndOutput(publicKey: SchnorrPublicKey): Boolean = {
    output.scriptPubKey match {
      case _: P2WSHWitnessSPKV0 =>
        val challenge = CryptoUtil.sha256(output.bytes).bytes
        publicKey.verify(challenge, sig)
      case _: P2WPKHWitnessSPKV0 | _: WitnessCommitment |
          _: UnassignedWitnessScriptPubKey | EmptyScriptPubKey |
          _: NonWitnessScriptPubKey =>
        false
    }
  }
}

object BobMessage extends VortexMessageFactory[BobMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(696973L)

  override val typeName: String = "BobMessage"

  override def fromTLVValue(value: ByteVector): BobMessage = {
    val iter = ValueIterator(value)

    val sig = iter.take(SchnorrDigitalSignature, 64)
    val output = iter.takeU16Prefixed[TransactionOutput](len =>
      TransactionOutput(iter.take(len)))

    BobMessage(sig, output)
  }
}

/** @param psbt Unsigned PSBT of the coinjoin transaction
  */
case class UnsignedPsbtMessage(psbt: PSBT) extends ServerVortexMessage {
  override val tpe: BigSizeUInt = UnsignedPsbtMessage.tpe

  override lazy val value: ByteVector = psbt.bytes
}

object UnsignedPsbtMessage extends VortexMessageFactory[UnsignedPsbtMessage] {
  override val tpe: BigSizeUInt = BigSizeUInt(696975L)

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
  override val tpe: BigSizeUInt = BigSizeUInt(696977L)

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
  override val tpe: BigSizeUInt = BigSizeUInt(696979L)

  override val typeName: String = "SignedTxMessage"

  override def fromTLVValue(value: ByteVector): SignedTxMessage = {
    val transaction = Transaction(value)

    SignedTxMessage(transaction)
  }
}
