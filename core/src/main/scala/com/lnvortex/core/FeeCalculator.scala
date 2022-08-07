package com.lnvortex.core

import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.wallet.fee._

trait FeeCalculator {

  def inputFee(
      feeRate: SatoshisPerVirtualByte,
      inputScriptType: ScriptType): CurrencyUnit =
    feeRate * getScriptTypeInputSize(inputScriptType)

  def outputFee(
      feeRate: SatoshisPerVirtualByte,
      outputScriptType: ScriptType,
      coordinatorScriptType: ScriptType,
      numPeersOpt: Option[Int]): CurrencyUnit = {
    require(numPeersOpt.forall(_ > 0), "Number of peers must be greater than 0")
    val outputSize = getScriptTypeOutputSize(outputScriptType)

    val coordinatorOutputSize = getScriptTypeOutputSize(coordinatorScriptType)
    val txOverhead = 10.5

    val peerShare = numPeersOpt match {
      case Some(numPeers) =>
        (coordinatorOutputSize + txOverhead) / numPeers.toDouble
      case None => 0.0
    }

    feeRate * (outputSize + peerShare).ceil.toLong
  }

  def changeOutputFee(
      feeRate: SatoshisPerVirtualByte,
      changeScriptType: ScriptType): CurrencyUnit =
    feeRate * getScriptTypeOutputSize(changeScriptType)

  /** Returns the expected size of an input for the script type in vbytes
    *
    * @see https://twitter.com/murchandamus/status/1262062602298916865/photo/1
    */
  def getScriptTypeInputSize(scriptType: ScriptType): Int = {
    scriptType match {
      case tpe @ (ScriptType.NONSTANDARD | ScriptType.MULTISIG |
          ScriptType.CLTV | ScriptType.CSV |
          ScriptType.NONSTANDARD_IF_CONDITIONAL |
          ScriptType.NOT_IF_CONDITIONAL | ScriptType.MULTISIG_WITH_TIMEOUT |
          ScriptType.PUBKEY_WITH_TIMEOUT | ScriptType.NULLDATA |
          ScriptType.WITNESS_UNKNOWN | ScriptType.WITNESS_COMMITMENT |
          ScriptType.WITNESS_V0_SCRIPTHASH) =>
        throw new IllegalArgumentException(s"Unknown address type $tpe")
      case ScriptType.PUBKEY             => 113
      case ScriptType.PUBKEYHASH         => 148
      case ScriptType.SCRIPTHASH         => 91
      case ScriptType.WITNESS_V0_KEYHASH => 68
      case ScriptType.WITNESS_V1_TAPROOT => 58
    }
  }

  /** Returns the expected size of an output for the script type in vbytes
    *
    * @see https://twitter.com/murchandamus/status/1262062602298916865/photo/1
    */
  def getScriptTypeOutputSize(scriptType: ScriptType): Int = {
    scriptType match {
      case tpe @ (ScriptType.NONSTANDARD | ScriptType.MULTISIG |
          ScriptType.CLTV | ScriptType.CSV |
          ScriptType.NONSTANDARD_IF_CONDITIONAL |
          ScriptType.NOT_IF_CONDITIONAL | ScriptType.MULTISIG_WITH_TIMEOUT |
          ScriptType.PUBKEY_WITH_TIMEOUT | ScriptType.NULLDATA |
          ScriptType.WITNESS_UNKNOWN | ScriptType.WITNESS_COMMITMENT) =>
        throw new IllegalArgumentException(s"Unknown address type $tpe")
      case ScriptType.PUBKEY                => 44
      case ScriptType.PUBKEYHASH            => 34
      case ScriptType.SCRIPTHASH            => 32
      case ScriptType.WITNESS_V0_KEYHASH    => 31
      case ScriptType.WITNESS_V0_SCRIPTHASH => 43
      case ScriptType.WITNESS_V1_TAPROOT    => 43
    }
  }
}

object FeeCalculator extends FeeCalculator
