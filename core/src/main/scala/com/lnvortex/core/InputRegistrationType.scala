package com.lnvortex.core

import org.bitcoins.core.number.UInt16
import org.bitcoins.crypto._
import scodec.bits.ByteVector

sealed abstract class InputRegistrationType extends NetworkElement {
  def uInt16: UInt16

  override val bytes: ByteVector = uInt16.bytes
}

object InputRegistrationType extends Factory[InputRegistrationType] {

  case object AsyncInputRegistrationType extends InputRegistrationType {
    override val uInt16: UInt16 = UInt16.zero
  }

  case object SynchronousInputRegistrationType extends InputRegistrationType {
    override val uInt16: UInt16 = UInt16.one
  }

  private val all =
    Vector(AsyncInputRegistrationType, SynchronousInputRegistrationType)

  def fromInt(int: Int): InputRegistrationType = {
    fromUInt16(UInt16(int))
  }

  def fromUInt16(uint16: UInt16): InputRegistrationType = {
    all
      .find(_.uInt16 == uint16)
      .getOrElse(throw new IllegalArgumentException(
        s"No InputRegistrationType found for $uint16"))
  }

  override def fromBytes(bytes: ByteVector): InputRegistrationType = {
    val num = UInt16(bytes)
    fromUInt16(num)
  }
}
