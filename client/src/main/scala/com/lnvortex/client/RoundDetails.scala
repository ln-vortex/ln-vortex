package com.lnvortex.client

import com.lnvortex.core.MixDetails
import org.bitcoins.crypto.SchnorrNonce

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

  def nextStage(initDetails: InitDetails): InputsRegistered =
    InputsRegistered(round, nonce, initDetails)
}

sealed trait InitializedRound extends RoundDetails {

  def round: MixDetails
  def nonce: SchnorrNonce
  def initDetails: InitDetails
}

case class InputsRegistered(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 3

  def nextStage: MixOutputRegistered =
    MixOutputRegistered(round, nonce, initDetails)
}

case class MixOutputRegistered(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 4

  def nextStage: PSBTSigned =
    PSBTSigned(round, nonce, initDetails)
}

case class PSBTSigned(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound {
  override val order: Int = 5

  def nextStage: NoDetails.type = NoDetails
}

object RoundDetails {

  def getNonceOpt(details: RoundDetails): Option[SchnorrNonce] = {
    details match {
      case NoDetails | _: KnownRound => None
      case ReceivedNonce(_, nonce)   => Some(nonce)
      case round: InitializedRound   => Some(round.nonce)
    }
  }

  def getInitDetailsOpt(details: RoundDetails): Option[InitDetails] = {
    details match {
      case NoDetails | _: KnownRound | _: ReceivedNonce => None
      case round: InitializedRound =>
        Some(round.initDetails)
    }
  }
}
