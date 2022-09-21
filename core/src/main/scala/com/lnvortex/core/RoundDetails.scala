package com.lnvortex.core

import com.lnvortex.core.api.TransactionType
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.{DoubleSha256DigestBE, SchnorrNonce, StringFactory}

import java.net.InetSocketAddress

sealed trait RoundDetails {
  def order: Int = status.order
  def status: ClientStatus
  def transactionTypes: Vector[TransactionType]
  def requeue: Boolean
  def setRequeue(requeue: Boolean): RoundDetails
}

sealed trait PendingRoundDetails extends RoundDetails {
  def round: RoundParameters

  override def transactionTypes: Vector[TransactionType] = {
    TransactionType.calculate(round.inputType, round.outputType)
  }

  def updateFeeRate(feeRate: SatoshisPerVirtualByte): PendingRoundDetails
}

case class NoDetails(requeue: Boolean) extends RoundDetails {
  override val status: ClientStatus = ClientStatus.NoDetails
  override val transactionTypes: Vector[TransactionType] = Vector.empty

  override def setRequeue(requeue: Boolean): NoDetails =
    copy(requeue = requeue)

  def nextStage(round: RoundParameters): KnownRound = {
    KnownRound(requeue, round)
  }
}

case class KnownRound(requeue: Boolean, round: RoundParameters)
    extends PendingRoundDetails {
  override val status: ClientStatus = ClientStatus.KnownRound

  override def setRequeue(requeue: Boolean): KnownRound =
    copy(requeue = requeue)

  def nextStage(nonce: SchnorrNonce): ReceivedNonce =
    ReceivedNonce(requeue, round, nonce)

  override def updateFeeRate(feeRate: SatoshisPerVirtualByte): KnownRound = {
    copy(round = round.copy(feeRate = feeRate))
  }
}

case class ReceivedNonce(
    requeue: Boolean,
    round: RoundParameters,
    nonce: SchnorrNonce)
    extends PendingRoundDetails {
  override val status: ClientStatus = ClientStatus.ReceivedNonce

  override def setRequeue(requeue: Boolean): ReceivedNonce =
    copy(requeue = requeue)

  def nextStage(
      inputs: Vector[OutputReference],
      addressOpt: Option[BitcoinAddress],
      nodeIdOpt: Option[NodeId],
      peerAddrOpt: Option[InetSocketAddress]): InputsScheduled =
    InputsScheduled(requeue = requeue,
                    round = round,
                    nonce = nonce,
                    inputs = inputs,
                    addressOpt = addressOpt,
                    nodeIdOpt = nodeIdOpt,
                    peerAddrOpt = peerAddrOpt)

  override def updateFeeRate(feeRate: SatoshisPerVirtualByte): ReceivedNonce = {
    copy(round = round.copy(feeRate = feeRate))
  }
}

case class InputsScheduled(
    requeue: Boolean,
    round: RoundParameters,
    nonce: SchnorrNonce,
    inputs: Vector[OutputReference],
    addressOpt: Option[BitcoinAddress],
    nodeIdOpt: Option[NodeId],
    peerAddrOpt: Option[InetSocketAddress])
    extends PendingRoundDetails {
  override val status: ClientStatus = ClientStatus.InputsScheduled

  override def setRequeue(requeue: Boolean): InputsScheduled =
    copy(requeue = requeue)

  def nextStage(
      initDetails: InitDetails,
      inputFee: CurrencyUnit,
      outputFee: CurrencyUnit,
      changeOutputFee: CurrencyUnit): InputsRegistered =
    InputsRegistered(requeue = requeue,
                     round = round,
                     inputFee = inputFee,
                     outputFee = outputFee,
                     changeOutputFee = changeOutputFee,
                     nonce = nonce,
                     initDetails = initDetails)

  override def updateFeeRate(
      feeRate: SatoshisPerVirtualByte): InputsScheduled = {
    copy(round = round.copy(feeRate = feeRate))
  }
}

sealed trait InitializedRound extends RoundDetails {

  def round: RoundParameters
  def inputFee: CurrencyUnit
  def outputFee: CurrencyUnit
  def changeOutputFee: CurrencyUnit
  def nonce: SchnorrNonce
  def initDetails: InitDetails

  override def transactionTypes: Vector[TransactionType] = {
    TransactionType.calculate(round.inputType, round.outputType)
  }

  def expectedAmtBack(numRemixes: Int, numNewEntrants: Int): CurrencyUnit = {
    initDetails.changeSpkOpt match {
      case Some(_) =>
        val totalNewEntrantFee = Satoshis(numRemixes) * (inputFee + outputFee)
        val newEntrantFee = totalNewEntrantFee / Satoshis(numNewEntrants)

        val excessAfterChange =
          initDetails.inputAmt - round.amount - round.coordinatorFee - (Satoshis(
            initDetails.inputs.size) * inputFee) - outputFee - changeOutputFee - newEntrantFee

        if (excessAfterChange >= Policy.dustThreshold)
          excessAfterChange
        else Satoshis.zero
      case None => Satoshis.zero
    }
  }

  def restartRound(
      round: RoundParameters,
      nonce: SchnorrNonce): InputsScheduled =
    InputsScheduled(
      requeue = requeue,
      round = round,
      nonce = nonce,
      inputs = initDetails.inputs,
      addressOpt = initDetails.addressOpt,
      nodeIdOpt = initDetails.nodeIdOpt,
      peerAddrOpt = initDetails.peerAddrOpt
    )
}

case class InputsRegistered(
    requeue: Boolean,
    round: RoundParameters,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeOutputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val status: ClientStatus = ClientStatus.InputsRegistered

  override def setRequeue(requeue: Boolean): InputsRegistered =
    copy(requeue = requeue)

  def nextStage: OutputRegistered =
    OutputRegistered(requeue = requeue,
                     round = round,
                     inputFee = inputFee,
                     outputFee = outputFee,
                     changeOutputFee = changeOutputFee,
                     nonce = nonce,
                     initDetails = initDetails)
}

case class OutputRegistered(
    requeue: Boolean,
    round: RoundParameters,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeOutputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val status: ClientStatus = ClientStatus.OutputRegistered

  override def setRequeue(requeue: Boolean): OutputRegistered =
    copy(requeue = requeue)

  def nextStage(psbt: PSBT): PSBTSigned =
    PSBTSigned(requeue = requeue,
               round = round,
               inputFee = inputFee,
               outputFee = outputFee,
               changeOutputFee = changeOutputFee,
               nonce = nonce,
               initDetails = initDetails,
               psbt = psbt)
}

case class PSBTSigned(
    requeue: Boolean,
    round: RoundParameters,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeOutputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails,
    psbt: PSBT)
    extends InitializedRound {
  override val status: ClientStatus = ClientStatus.PSBTSigned

  val txId: DoubleSha256DigestBE = psbt.transaction.txIdBE

  val targetOutpoint: TransactionOutPoint = {
    val vout = UInt32(
      psbt.transaction.outputs.indexWhere(
        _.scriptPubKey == initDetails.targetOutput.scriptPubKey))

    TransactionOutPoint(txId, vout)
  }

  val changeOutpointOpt: Option[TransactionOutPoint] =
    initDetails.changeSpkOpt.map { spk =>
      val vout =
        UInt32(psbt.transaction.outputs.indexWhere(_.scriptPubKey == spk))

      TransactionOutPoint(txId, vout)
    }

  lazy val spks: Vector[ScriptPubKey] = {
    initDetails.changeSpkOpt.toVector :+ initDetails.targetOutput.scriptPubKey
  }

  lazy val targetSpk: ScriptPubKey = initDetails.targetOutput.scriptPubKey

  override def setRequeue(requeue: Boolean): PSBTSigned =
    copy(requeue = requeue)

  def nextStage: NoDetails.type = NoDetails
}

object RoundDetails {

  def getRoundParamsOpt(details: RoundDetails): Option[RoundParameters] = {
    details match {
      case _: NoDetails               => None
      case known: KnownRound          => Some(known.round)
      case ReceivedNonce(_, round, _) => Some(round)
      case scheduled: InputsScheduled => Some(scheduled.round)
      case round: InitializedRound    => Some(round.round)
    }
  }

  def getNonceOpt(details: RoundDetails): Option[SchnorrNonce] = {
    details match {
      case _: NoDetails | _: KnownRound => None
      case ReceivedNonce(_, _, nonce)   => Some(nonce)
      case scheduled: InputsScheduled   => Some(scheduled.nonce)
      case round: InitializedRound      => Some(round.nonce)
    }
  }

  def getInitDetailsOpt(details: RoundDetails): Option[InitDetails] = {
    details match {
      case _: NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled =>
        None
      case round: InitializedRound =>
        Some(round.initDetails)
    }
  }
}

sealed abstract class ClientStatus(val order: Int)

object ClientStatus extends StringFactory[ClientStatus] {

  /** The client hasn't learned the details of the round yet */
  case object NoDetails extends ClientStatus(0)

  /** The client has received the details of the round */
  case object KnownRound extends ClientStatus(1)

  /** Intermediate step during queueing coins. This nonce will be unique to the
    * user and used for blind signing
    */
  case object ReceivedNonce extends ClientStatus(2)

  /** The user has scheduled inputs to be registered. Once the coordinator sends
    * the [[AskInputs]] message it register them.
    */
  case object InputsScheduled extends ClientStatus(3)

  /** After the [[AskInputs]] message has been received and the client sends its
    * inputs to the coordinator its inputs will be registered. This is the first
    * state when the round begins.
    */
  case object InputsRegistered extends ClientStatus(4)

  /** Intermediate step during the round. The client has registered its output
    * with unblinded signature under its alternate Bob identity
    */
  case object OutputRegistered extends ClientStatus(5)

  /** Final stage during the round. The client has received the PSBT and signed
    * it. It will send it back to the coordinator to complete the transaction
    * and broadcast
    */
  case object PSBTSigned extends ClientStatus(6)

  val all: Vector[ClientStatus] = Vector(NoDetails,
                                         KnownRound,
                                         ReceivedNonce,
                                         InputsScheduled,
                                         InputsRegistered,
                                         OutputRegistered,
                                         PSBTSigned)

  override def fromStringOpt(string: String): Option[ClientStatus] = {
    val searchString = string.trim.toLowerCase
    all.find(_.toString.toLowerCase == searchString)
  }

  override def fromString(string: String): ClientStatus = {
    fromStringOpt(string).getOrElse(
      sys.error(s"Could not find a ClientStatus for string $string"))
  }
}
