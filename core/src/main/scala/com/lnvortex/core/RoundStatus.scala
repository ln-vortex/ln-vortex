package com.lnvortex.core

import org.bitcoins.crypto.StringFactory

sealed abstract class RoundStatus

object RoundStatus extends StringFactory[RoundStatus] {

  case object Pending extends RoundStatus

  case object AlicesRegistered extends RoundStatus

  case object OutputsRegistered extends RoundStatus

  case object SigningPhase extends RoundStatus

  case object Signed extends RoundStatus

  case object Canceled extends RoundStatus

  val all: Vector[RoundStatus] = Vector(Pending,
                                        AlicesRegistered,
                                        OutputsRegistered,
                                        SigningPhase,
                                        Signed,
                                        Canceled)

  override def fromStringOpt(string: String): Option[RoundStatus] = {
    all.find(_.toString.toLowerCase == string.toLowerCase)
  }

  override def fromString(string: String): RoundStatus = {
    fromStringOpt(string) match {
      case Some(value) => value
      case None =>
        sys.error(s"Could not find a RoundStatus for $string")
    }
  }
}
