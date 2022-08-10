package com.lnvortex.core

import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto.{SchnorrNonce, StringFactory}

import java.net.InetSocketAddress

sealed trait RoundDetails {
  def order: Int
  def status: ClientStatus
}

case object NoDetails extends RoundDetails {
  override val order: Int = 0
  override val status: ClientStatus = ClientStatus.NoDetails

  def nextStage(round: RoundParameters): KnownRound = {
    KnownRound(round)
  }
}

case class KnownRound(round: RoundParameters) extends RoundDetails {
  override val order: Int = 1
  override val status: ClientStatus = ClientStatus.KnownRound

  def nextStage(nonce: SchnorrNonce): ReceivedNonce =
    ReceivedNonce(round, nonce)
}

case class ReceivedNonce(round: RoundParameters, nonce: SchnorrNonce)
    extends RoundDetails {
  override val order: Int = 2
  override val status: ClientStatus = ClientStatus.ReceivedNonce

  def nextStage(
      inputs: Vector[OutputReference],
      addressOpt: Option[BitcoinAddress],
      nodeIdOpt: Option[NodeId],
      peerAddrOpt: Option[InetSocketAddress]): InputsScheduled =
    InputsScheduled(round, nonce, inputs, addressOpt, nodeIdOpt, peerAddrOpt)
}

case class InputsScheduled(
    round: RoundParameters,
    nonce: SchnorrNonce,
    inputs: Vector[OutputReference],
    addressOpt: Option[BitcoinAddress],
    nodeIdOpt: Option[NodeId],
    peerAddrOpt: Option[InetSocketAddress])
    extends RoundDetails {
  override val order: Int = 3
  override val status: ClientStatus = ClientStatus.InputsScheduled

  def nextStage(
      initDetails: InitDetails,
      inputFee: CurrencyUnit,
      outputFee: CurrencyUnit,
      changeOutputFee: CurrencyUnit): InputsRegistered =
    InputsRegistered(round = round,
                     inputFee = inputFee,
                     outputFee = outputFee,
                     changeOutputFee = changeOutputFee,
                     nonce = nonce,
                     initDetails = initDetails)
}

sealed trait InitializedRound extends RoundDetails {

  def round: RoundParameters
  def inputFee: CurrencyUnit
  def outputFee: CurrencyUnit
  def changeOutputFee: CurrencyUnit
  def nonce: SchnorrNonce
  def initDetails: InitDetails

  // todo add tests
  def expectedAmtBackOpt(
      numRemixes: Int,
      numNewEntrants: Int): Option[CurrencyUnit] = {
    initDetails.changeSpkOpt.flatMap { _ =>
      val totalNewEntrantFee = Satoshis(numRemixes) * (inputFee + outputFee)
      val newEntrantFee = totalNewEntrantFee / Satoshis(numNewEntrants)

      val excessAfterChange =
        initDetails.inputAmt - round.amount - round.coordinatorFee - (Satoshis(
          initDetails.inputs.size) * inputFee) - outputFee - changeOutputFee - newEntrantFee

      if (excessAfterChange >= Policy.dustThreshold)
        Some(excessAfterChange)
      else None
    }
  }

  def restartRound(
      round: RoundParameters,
      nonce: SchnorrNonce): InputsScheduled =
    InputsScheduled(
      round = round,
      nonce = nonce,
      inputs = initDetails.inputs,
      addressOpt = initDetails.addressOpt,
      nodeIdOpt = initDetails.nodeIdOpt,
      peerAddrOpt = initDetails.peerAddrOpt
    )
}

case class InputsRegistered(
    round: RoundParameters,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeOutputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 4
  override val status: ClientStatus = ClientStatus.InputsRegistered

  def nextStage: OutputRegistered =
    OutputRegistered(round,
                     inputFee,
                     outputFee,
                     changeOutputFee,
                     nonce,
                     initDetails)
}

case class OutputRegistered(
    round: RoundParameters,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeOutputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 5
  override val status: ClientStatus = ClientStatus.OutputRegistered

  def nextStage(psbt: PSBT): PSBTSigned =
    PSBTSigned(round,
               inputFee,
               outputFee,
               changeOutputFee,
               nonce,
               initDetails,
               psbt)
}

case class PSBTSigned(
    round: RoundParameters,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeOutputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails,
    psbt: PSBT)
    extends InitializedRound {
  override val order: Int = 6
  override val status: ClientStatus = ClientStatus.PSBTSigned

  val channelOutpoint: TransactionOutPoint = {
    val txId = psbt.transaction.txId
    val vout = UInt32(
      psbt.transaction.outputs.indexWhere(
        _.scriptPubKey == initDetails.targetOutput.scriptPubKey))

    TransactionOutPoint(txId, vout)
  }

  val changeOutpointOpt: Option[TransactionOutPoint] =
    initDetails.changeSpkOpt.map { spk =>
      val txId = psbt.transaction.txId
      val vout =
        UInt32(psbt.transaction.outputs.indexWhere(_.scriptPubKey == spk))

      TransactionOutPoint(txId, vout)
    }

  lazy val spks: Vector[ScriptPubKey] = {
    initDetails.changeSpkOpt.toVector :+ initDetails.targetOutput.scriptPubKey
  }

  lazy val targetSpk: ScriptPubKey = initDetails.targetOutput.scriptPubKey

  def nextStage: NoDetails.type = NoDetails
}

object RoundDetails {

  def getRoundParamsOpt(details: RoundDetails): Option[RoundParameters] = {
    details match {
      case NoDetails                  => None
      case known: KnownRound          => Some(known.round)
      case ReceivedNonce(round, _)    => Some(round)
      case scheduled: InputsScheduled => Some(scheduled.round)
      case round: InitializedRound    => Some(round.round)
    }
  }

  def getNonceOpt(details: RoundDetails): Option[SchnorrNonce] = {
    details match {
      case NoDetails | _: KnownRound  => None
      case ReceivedNonce(_, nonce)    => Some(nonce)
      case scheduled: InputsScheduled => Some(scheduled.nonce)
      case round: InitializedRound    => Some(round.nonce)
    }
  }

  def getInitDetailsOpt(details: RoundDetails): Option[InitDetails] = {
    details match {
      case NoDetails | _: KnownRound | _: ReceivedNonce | _: InputsScheduled =>
        None
      case round: InitializedRound =>
        Some(round.initDetails)
    }
  }
}

sealed abstract class ClientStatus

object ClientStatus extends StringFactory[ClientStatus] {

  /** The client hasn't learned the details of the round yet */
  case object NoDetails extends ClientStatus

  /** The client has received the details of the round */
  case object KnownRound extends ClientStatus

  /** Intermediate step during queueing coins.
    * This nonce will be unique to the user and used for blind signing
    */
  case object ReceivedNonce extends ClientStatus

  /** The user has scheduled inputs to be registered.
    * Once the coordinator sends the [[AskInputs]] message it register them.
    */
  case object InputsScheduled extends ClientStatus

  /** After the [[AskInputs]] message has been received and the client sends its
    * inputs to the coordinator its inputs will be registered.
    * This is the first state when the round begins.
    */
  case object InputsRegistered extends ClientStatus

  /** Intermediate step during the round.
    * The client has registered its output with unblinded signature
    * under its alternate Bob identity
    */
  case object OutputRegistered extends ClientStatus

  /** Final stage during the round.
    * The client has received the PSBT and signed it.
    * It will send it back to the coordinator to
    * complete the transaction and broadcast
    */
  case object PSBTSigned extends ClientStatus

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
