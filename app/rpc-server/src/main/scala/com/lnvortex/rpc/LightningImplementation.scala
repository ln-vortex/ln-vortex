package com.lnvortex.rpc

import org.bitcoins.crypto.StringFactory

sealed abstract class LightningImplementation

object LightningImplementation extends StringFactory[LightningImplementation] {

  case object LND extends LightningImplementation
  case object CLN extends LightningImplementation

  def all: Vector[LightningImplementation] = Vector(LND, CLN)

  override def fromStringOpt(
      string: String): Option[LightningImplementation] = {
    val searchString = string.toUpperCase
    all.find(_.toString.toUpperCase == searchString)
  }

  override def fromString(string: String): LightningImplementation = {
    fromStringOpt(string).getOrElse(
      sys.error(s"Could not find a LightningImplementation for string $string"))
  }
}
