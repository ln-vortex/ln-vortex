package com.lnvortex.client

import com.lnvortex.core.MixAdvertisement

sealed trait RoundDetails[T, N <: RoundDetails[_, _]] {
  def order: Int
  def nextStage(t: T): N
}

case object NoDetails extends RoundDetails[MixAdvertisement, KnownRound] {
  override val order: Int = 0

  override def nextStage(round: MixAdvertisement): KnownRound = {
    KnownRound(round)
  }
}

case class KnownRound(round: MixAdvertisement)
    extends RoundDetails[InitDetails, InputsRegistered] {
  override val order: Int = 1

  override def nextStage(initDetails: InitDetails): InputsRegistered =
    InputsRegistered(round, initDetails)
}

sealed trait InitializedRound[N <: RoundDetails[_, _]]
    extends RoundDetails[Unit, N] {

  def nextStage(): N = nextStage(())
}

case class InputsRegistered(round: MixAdvertisement, initDetails: InitDetails)
    extends InitializedRound[MixOutputRegistered] {
  override val order: Int = 2

  override def nextStage(t: Unit): MixOutputRegistered =
    MixOutputRegistered(round, initDetails)
}

case class MixOutputRegistered(
    round: MixAdvertisement,
    initDetails: InitDetails)
    extends InitializedRound[PSBTSigned] {
  override val order: Int = 3

  override def nextStage(t: Unit): PSBTSigned = PSBTSigned(round, initDetails)
}

case class PSBTSigned(round: MixAdvertisement, initDetails: InitDetails)
    extends InitializedRound[NoDetails.type] {
  override val order: Int = 4

  override def nextStage(t: Unit): NoDetails.type = NoDetails

}
