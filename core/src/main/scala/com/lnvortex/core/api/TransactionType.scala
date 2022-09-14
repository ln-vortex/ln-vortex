package com.lnvortex.core.api

import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto.StringFactory

sealed abstract class TransactionType

object TransactionType extends StringFactory[TransactionType] {

  /** Normal on-chain spend to an address */
  case object OnChain extends TransactionType

  /** Opens a Lightning Channel */
  case object ChannelOpen extends TransactionType

  val all: Vector[TransactionType] = Vector(OnChain, ChannelOpen)

  override def fromStringOpt(str: String): Option[TransactionType] = {
    val searchString = str.toLowerCase
    all.find(_.toString.toLowerCase == searchString)
  }

  override def fromString(string: String): TransactionType = {
    fromStringOpt(string).getOrElse(
      throw new IllegalArgumentException(
        s"Could not find TransactionType for string: $string"))
  }

  def calculate(
      input: ScriptType,
      output: ScriptType): Vector[TransactionType] = {
    (input, output) match {
      case (ScriptType.WITNESS_V1_TAPROOT, ScriptType.WITNESS_V1_TAPROOT) =>
        Vector(OnChain) // todo add channel open
      case (ScriptType.WITNESS_V0_KEYHASH, ScriptType.WITNESS_V1_TAPROOT) =>
        Vector(OnChain) // todo add channel open
      case (ScriptType.WITNESS_V1_TAPROOT, ScriptType.WITNESS_V0_KEYHASH) =>
        Vector(OnChain)
      case (ScriptType.WITNESS_V1_TAPROOT, ScriptType.WITNESS_V0_SCRIPTHASH) =>
        Vector(ChannelOpen)
      case (ScriptType.PUBKEYHASH, ScriptType.PUBKEYHASH) => Vector(OnChain)
      case (ScriptType.PUBKEYHASH, ScriptType.WITNESS_V0_SCRIPTHASH) =>
        Vector(OnChain, ChannelOpen)
      case (ScriptType.PUBKEYHASH, ScriptType.WITNESS_V0_KEYHASH) =>
        Vector(OnChain)
      case (ScriptType.SCRIPTHASH, ScriptType.PUBKEYHASH) => Vector(OnChain)
      case (ScriptType.SCRIPTHASH, ScriptType.WITNESS_V0_SCRIPTHASH) =>
        Vector(ChannelOpen)
      case (ScriptType.SCRIPTHASH, ScriptType.WITNESS_V0_KEYHASH) =>
        Vector(OnChain)
      case (ScriptType.WITNESS_V0_SCRIPTHASH, ScriptType.PUBKEYHASH) =>
        Vector(OnChain)
      case (ScriptType.WITNESS_V0_SCRIPTHASH,
            ScriptType.WITNESS_V0_SCRIPTHASH) =>
        Vector(ChannelOpen)
      case (ScriptType.WITNESS_V0_SCRIPTHASH, ScriptType.WITNESS_V0_KEYHASH) =>
        Vector(OnChain)
      case (ScriptType.WITNESS_V0_KEYHASH, ScriptType.PUBKEYHASH) =>
        Vector(OnChain)
      case (ScriptType.WITNESS_V0_KEYHASH, ScriptType.WITNESS_V0_SCRIPTHASH) =>
        Vector(ChannelOpen)
      case (ScriptType.WITNESS_V0_KEYHASH, ScriptType.WITNESS_V0_KEYHASH) =>
        Vector(OnChain)
      case (ScriptType.MULTISIG, ScriptType.PUBKEYHASH) => Vector(OnChain)
      case (ScriptType.MULTISIG, ScriptType.WITNESS_V0_SCRIPTHASH) =>
        Vector(ChannelOpen)
      case (ScriptType.MULTISIG, ScriptType.WITNESS_V0_KEYHASH) =>
        Vector(OnChain)
      case (ScriptType.NONSTANDARD, ScriptType.PUBKEYHASH) => Vector(OnChain)
      case (ScriptType.NONSTANDARD, ScriptType.WITNESS_V0_SCRIPTHASH) =>
        Vector(ChannelOpen)
      case (ScriptType.NONSTANDARD, ScriptType.WITNESS_V0_KEYHASH) =>
        Vector(OnChain)
      case (ScriptType.NONSTANDARD, ScriptType.NONSTANDARD) => Vector(OnChain)
      case (ScriptType.NONSTANDARD, ScriptType.MULTISIG)    => Vector(OnChain)
      case (ScriptType.PUBKEYHASH, ScriptType.NONSTANDARD)  => Vector(OnChain)
      case _ =>
        throw new IllegalArgumentException(
          s"Could not find TransactionType for input: $input output: $output")
    }
  }
}
