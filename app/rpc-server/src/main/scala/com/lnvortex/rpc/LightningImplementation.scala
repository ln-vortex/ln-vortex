package com.lnvortex.rpc

import org.bitcoins.crypto.StringFactory

sealed abstract class LightningImplementation

object LightningImplementation extends StringFactory[LightningImplementation] {

  case object LND extends LightningImplementation
  case object CLN extends LightningImplementation
  case object Bitcoind extends LightningImplementation

  def all: Vector[LightningImplementation] = Vector(LND, CLN, Bitcoind)

  override def fromStringOpt(
      string: String): Option[LightningImplementation] = {
    string.toLowerCase match {
      case "lnd"            => Some(LND)
      case "cln"            => Some(CLN)
      case "c-lightning"    => Some(CLN)
      case "clightning"     => Some(CLN)
      case "c lightning"    => Some(CLN)
      case "core lightning" => Some(CLN)
      case "core-lightning" => Some(CLN)
      case "bitcoin"        => Some(Bitcoind)
      case "bitcoind"       => Some(Bitcoind)
      case "bitcoin-core"   => Some(Bitcoind)
      case "bitcoin core"   => Some(Bitcoind)
      case _                => None
    }
  }

  override def fromString(string: String): LightningImplementation = {
    fromStringOpt(string).getOrElse(
      sys.error(s"Could not find a LightningImplementation for string $string"))
  }
}
