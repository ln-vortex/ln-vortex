package com.lnvortex.client

import com.lnvortex.core.MixDetails
import org.bitcoins.crypto.SchnorrNonce

sealed trait RoundDetails[T, N <: RoundDetails[_, _]] {
  def order: Int
  def nextStage(t: T): N

  def nonceOpt: Option[SchnorrNonce]
}

case object NoDetails extends RoundDetails[MixDetails, KnownRound] {
  override val order: Int = 0
  override val nonceOpt: Option[SchnorrNonce] = None

  override def nextStage(round: MixDetails): KnownRound = {
    KnownRound(round)
  }

}

case class KnownRound(round: MixDetails)
    extends RoundDetails[SchnorrNonce, ReceivedNonce] {
  override val order: Int = 1
  override val nonceOpt: Option[SchnorrNonce] = None

  override def nextStage(nonce: SchnorrNonce): ReceivedNonce =
    ReceivedNonce(round, nonce)
}

case class ReceivedNonce(round: MixDetails, nonce: SchnorrNonce)
    extends RoundDetails[InitDetails, InputsRegistered] {
  override val order: Int = 2
  override val nonceOpt: Option[SchnorrNonce] = Some(nonce)

  override def nextStage(initDetails: InitDetails): InputsRegistered =
    InputsRegistered(round, nonce, initDetails)
}

sealed trait InitializedRound[N <: RoundDetails[_, _]]
    extends RoundDetails[Unit, N] {

  def round: MixDetails
  def nonce: SchnorrNonce
  def initDetails: InitDetails

  override val nonceOpt: Option[SchnorrNonce] = Some(nonce)
  def nextStage(): N = nextStage(())
}

case class InputsRegistered(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound[MixOutputRegistered] {
  override val order: Int = 3

  override def nextStage(t: Unit): MixOutputRegistered =
    MixOutputRegistered(round, nonce, initDetails)
}

case class MixOutputRegistered(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound[PSBTSigned] {
  override val order: Int = 4

  override def nextStage(t: Unit): PSBTSigned =
    PSBTSigned(round, nonce, initDetails)
}

case class PSBTSigned(
    round: MixDetails,
    nonce: SchnorrNonce,
    initDetails: InitDetails)
    extends InitializedRound[NoDetails.type] {
  override val order: Int = 5

  override def nextStage(t: Unit): NoDetails.type = NoDetails

}
