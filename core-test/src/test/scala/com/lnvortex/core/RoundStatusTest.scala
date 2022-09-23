package com.lnvortex.core

import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class RoundStatusTest extends BitcoinSUnitTest {

  it must "have serialization symmetry" in {
    RoundStatus.all.foreach { status =>
      assert(RoundStatus.fromString(status.toString) == status)
    }
  }

  it must "fail to parse an invalid string" in {
    assertThrows[IllegalArgumentException](RoundStatus.fromString("foo"))
  }
}
