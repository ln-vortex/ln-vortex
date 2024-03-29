package com.lnvortex.core

import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
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

  // $COVERAGE-OFF$
  /** Returns the expected size of an input for the script type in vbytes
    *
    * @see
    *   https://twitter.com/murchandamus/status/1262062602298916865/photo/1
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
    * @see
    *   https://twitter.com/murchandamus/status/1262062602298916865/photo/1
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
  // $COVERAGE-ON$

  def calculateChangeOutput(
      roundParams: RoundParameters,
      isRemix: Boolean,
      numInputs: Int,
      numRemixes: Int,
      numNewEntrants: Int,
      inputAmount: CurrencyUnit,
      changeSpkOpt: Option[ScriptPubKey]): Either[CurrencyUnit,
                                                  TransactionOutput] = {
    if (isRemix) Left(Satoshis.zero)
    else {
      val feeRate = roundParams.feeRate
      val inputFee = this.inputFee(feeRate, roundParams.inputType)
      val updatedOutputFee = outputFee(feeRate,
                                       roundParams.outputType,
                                       roundParams.changeType,
                                       Some(numNewEntrants))
      val totalNewEntrantFee =
        Satoshis(numRemixes) * (inputFee + outputFee(feeRate,
                                                     roundParams.outputType,
                                                     roundParams.changeType,
                                                     None))
      val newEntrantFee = totalNewEntrantFee / Satoshis(numNewEntrants)
      val excess =
        inputAmount - roundParams.amount - roundParams.coordinatorFee - (Satoshis(
          numInputs) * inputFee) - updatedOutputFee - newEntrantFee

      changeSpkOpt match {
        case Some(changeSpk) =>
          val dummy = TransactionOutput(Satoshis.zero, changeSpk)
          val changeCost = feeRate * dummy.byteSize

          val excessAfterChange = excess - changeCost

          if (excessAfterChange >= Policy.dustThreshold)
            Right(TransactionOutput(excessAfterChange, changeSpk))
          else Left(excess)
        case None => Left(excess)
      }
    }
  }
}

object FeeCalculator extends FeeCalculator
