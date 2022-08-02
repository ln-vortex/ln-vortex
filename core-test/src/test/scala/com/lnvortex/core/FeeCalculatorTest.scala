package com.lnvortex.core

import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class FeeCalculatorTest extends BitcoinSUnitTest {
  private val feeRate = SatoshisPerVirtualByte.fromLong(1)
  private val feeRate2 = SatoshisPerVirtualByte.fromLong(35)

  it must "correctly calculate input fee" in {
    val inputFee = FeeCalculator.inputFee(feeRate, WITNESS_V1_TAPROOT)
    assert(inputFee == Satoshis(58))

    val inputFee2 = FeeCalculator.inputFee(feeRate2, WITNESS_V0_KEYHASH)
    assert(inputFee2 == Satoshis(2380))

    assertThrows[IllegalArgumentException](
      FeeCalculator.inputFee(feeRate, WITNESS_V0_SCRIPTHASH))

    assertThrows[IllegalArgumentException](
      FeeCalculator.inputFee(feeRate, NONSTANDARD_IF_CONDITIONAL))
  }

  it must "correctly calculate change output fee" in {
    val inputFee = FeeCalculator.changeOutputFee(feeRate, WITNESS_V1_TAPROOT)
    assert(inputFee == Satoshis(43))

    val inputFee2 = FeeCalculator.changeOutputFee(feeRate2, WITNESS_V0_KEYHASH)
    assert(inputFee2 == Satoshis(1085))

    assertThrows[IllegalArgumentException](
      FeeCalculator.changeOutputFee(feeRate, NONSTANDARD_IF_CONDITIONAL))
  }

  it must "correctly calculate output fee" in {
    val inputFee =
      FeeCalculator.outputFee(feeRate = feeRate,
                              outputScriptType = WITNESS_V1_TAPROOT,
                              coordinatorScriptType = WITNESS_V1_TAPROOT,
                              numPeers = 1)
    assert(inputFee == Satoshis(97))

    val inputFee2 =
      FeeCalculator.outputFee(feeRate = feeRate,
                              outputScriptType = WITNESS_V0_SCRIPTHASH,
                              coordinatorScriptType = WITNESS_V0_KEYHASH,
                              numPeers = 5)
    assert(inputFee2 == Satoshis(52))

    assertThrows[IllegalArgumentException](
      FeeCalculator
        .outputFee(feeRate, NONSTANDARD_IF_CONDITIONAL, WITNESS_V1_TAPROOT, 1))

    assertThrows[IllegalArgumentException](
      FeeCalculator
        .outputFee(feeRate, WITNESS_V1_TAPROOT, NONSTANDARD_IF_CONDITIONAL, 1))

    assertThrows[IllegalArgumentException](
      FeeCalculator
        .outputFee(feeRate, WITNESS_V1_TAPROOT, WITNESS_V1_TAPROOT, 0))

    assertThrows[IllegalArgumentException](
      FeeCalculator
        .outputFee(feeRate, WITNESS_V1_TAPROOT, WITNESS_V1_TAPROOT, -1))
  }

}
