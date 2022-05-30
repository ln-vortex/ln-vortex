package com.lnvortex.core

import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.ln.node.NodeId
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

  def nextStage(round: MixDetails): KnownRound = {
    KnownRound(round)
  }
}

case class KnownRound(round: MixDetails) extends RoundDetails {
  override val order: Int = 1
  override val status: ClientStatus = ClientStatus.KnownRound

  def nextStage(nonce: SchnorrNonce): ReceivedNonce =
    ReceivedNonce(round, nonce)
}

case class ReceivedNonce(round: MixDetails, nonce: SchnorrNonce)
    extends RoundDetails {
  override val order: Int = 2
  override val status: ClientStatus = ClientStatus.ReceivedNonce

  def nextStage(
      inputs: Vector[OutputReference],
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): InputsScheduled =
    InputsScheduled(round, nonce, inputs, nodeId, peerAddrOpt)
}

case class InputsScheduled(
    round: MixDetails,
    nonce: SchnorrNonce,
    inputs: Vector[OutputReference],
    nodeId: NodeId,
    peerAddrOpt: Option[InetSocketAddress])
    extends RoundDetails {
  override val order: Int = 3
  override val status: ClientStatus = ClientStatus.InputsScheduled

  def nextStage(
      initDetails: InitDetails,
      inputFee: CurrencyUnit,
      outputFee: CurrencyUnit): InputsRegistered =
    InputsRegistered(round, inputFee, outputFee, nonce, initDetails)
}

sealed trait InitializedRound extends RoundDetails {

  def round: MixDetails
  def inputFee: CurrencyUnit
  def outputFee: CurrencyUnit
  def nonce: SchnorrNonce
  def initDetails: InitDetails

  // todo add tests
  def expectedAmtBackOpt: Option[CurrencyUnit] =
    initDetails.changeSpkOpt.flatMap { _ =>
      val excessAfterChange =
        initDetails.inputAmt - round.amount - round.mixFee - (Satoshis(
          initDetails.inputs.size) * inputFee) - outputFee - outputFee

      if (excessAfterChange > Policy.dustThreshold)
        Some(excessAfterChange)
      else None
    }

  def restartRound(round: MixDetails, nonce: SchnorrNonce): InputsScheduled =
    InputsScheduled(round = round,
                    nonce = nonce,
                    inputs = initDetails.inputs,
                    nodeId = initDetails.nodeId,
                    peerAddrOpt = initDetails.peerAddrOpt)
}

case class InputsRegistered(
    round: MixDetails,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 4
  override val status: ClientStatus = ClientStatus.InputsRegistered

  def nextStage: MixOutputRegistered =
    MixOutputRegistered(round, inputFee, outputFee, nonce, initDetails)
}

case class MixOutputRegistered(
    round: MixDetails,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 5
  override val status: ClientStatus = ClientStatus.MixOutputRegistered

  def nextStage(psbt: PSBT): PSBTSigned =
    PSBTSigned(round, inputFee, outputFee, nonce, initDetails, psbt)
}

case class PSBTSigned(
    round: MixDetails,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
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
        _.scriptPubKey == initDetails.mixOutput.scriptPubKey))

    TransactionOutPoint(txId, vout)
  }

  def nextStage: NoDetails.type = NoDetails
}

object RoundDetails {

  def getMixDetailsOpt(details: RoundDetails): Option[MixDetails] = {
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
    * This is the first state when the mixing begins.
    */
  case object InputsRegistered extends ClientStatus

  /** Intermediate step during the mix.
    * The client has registered its output with unblinded signature
    * under its alternate Bob identity
    */
  case object MixOutputRegistered extends ClientStatus

  /** Final stage during the mix.
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
                                         MixOutputRegistered,
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
