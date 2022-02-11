package com.lnvortex.rpc

import org.bitcoins.crypto.StringFactory

sealed abstract class LightningImplementation

object LightningImplementation extends StringFactory[LightningImplementation] {

  case object LND extends LightningImplementation
  case object CLightning extends LightningImplementation

  val all = Vector(LND, CLightning)

  override def fromStringOpt(
      string: String): Option[LightningImplementation] = {
    all.find(_.toString.toLowerCase == string.toLowerCase)
  }

  override def fromString(string: String): LightningImplementation = {
    fromStringOpt(string).getOrElse(
      sys.error(s"Could not find a LightningImplementation for string $string"))
  }
}
