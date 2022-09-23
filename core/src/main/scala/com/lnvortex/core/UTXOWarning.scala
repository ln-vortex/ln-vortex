package com.lnvortex.core

import org.bitcoins.crypto.StringFactory

sealed abstract class UTXOWarning(val warning: String)

object UTXOWarning extends StringFactory[UTXOWarning] {

  case object AddressReuse extends UTXOWarning("Address has been reused")

  val all: Vector[UTXOWarning] = Vector(AddressReuse)

  override def fromStringOpt(string: String): Option[UTXOWarning] = {
    val searchString = string.toLowerCase.trim

    all
      .find(_.toString.toLowerCase == string.toLowerCase)
      .orElse(all.find(_.warning.toLowerCase == searchString))
  }

  override def fromString(string: String): UTXOWarning = {
    fromStringOpt(string)
      .getOrElse(
        throw new IllegalArgumentException(
          s"Could not find UTXOWarning for string: $string"))
  }
}
