package com.lnvortex.client

import com.lnvortex.core.MixDetails
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto.SchnorrNonce

import java.net.InetSocketAddress

sealed trait RoundDetails {
  def order: Int
}

case object NoDetails extends RoundDetails {
  override val order: Int = 0

  def nextStage(round: MixDetails): KnownRound = {
    KnownRound(round)
  }
}

case class KnownRound(round: MixDetails) extends RoundDetails {
  override val order: Int = 1

  def nextStage(nonce: SchnorrNonce): ReceivedNonce =
    ReceivedNonce(round, nonce)
}

case class ReceivedNonce(round: MixDetails, nonce: SchnorrNonce)
    extends RoundDetails {
  override val order: Int = 2

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

  def nextStage(initDetails: InitDetails): InputsRegistered =
    InputsRegistered(round, nonce, initDetails)
}

sealed trait InitializedRound extends RoundDetails {

  def round: MixDetails
  def nonce: SchnorrNonce
  def initDetails: InitDetails

  def expectedAmtBackOpt: Option[CurrencyUnit] = {
    val excessAfterChange =
      initDetails.inputAmt - round.amount - round.mixFee - (Satoshis(
        initDetails.inputs.size) * round.inputFee) - round.outputFee - round.outputFee

    if (excessAfterChange > Policy.dustThreshold)
      Some(excessAfterChange)
    else None
  }
}

case class InputsRegistered(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 4

  def nextStage: MixOutputRegistered =
    MixOutputRegistered(round, nonce, initDetails)
}

case class MixOutputRegistered(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 5

  def nextStage(psbt: PSBT): PSBTSigned =
    PSBTSigned(round, nonce, initDetails, psbt)
}

case class PSBTSigned(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails,
    psbt: PSBT)
    extends InitializedRound {
  override val order: Int = 6

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
