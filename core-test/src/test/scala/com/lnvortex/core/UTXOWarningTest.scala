package com.lnvortex.core

import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class UTXOWarningTest extends BitcoinSUnitTest {

  it must "have serialization symmetry" in {
    UTXOWarning.all.foreach { status =>
      assert(UTXOWarning.fromString(status.toString) == status)
    }
  }

  it must "fail to parse an invalid string" in {
    assertThrows[IllegalArgumentException](UTXOWarning.fromString("foo"))
  }
}
