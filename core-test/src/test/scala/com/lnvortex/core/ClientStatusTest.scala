package com.lnvortex.core

import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class ClientStatusTest extends BitcoinSUnitTest {

  it must "have serialization symmetry" in {
    ClientStatus.all.foreach { status =>
      assert(ClientStatus.fromString(status.toString) == status)
    }
  }

  it must "fail to parse an invalid string" in {
    assertThrows[IllegalArgumentException](ClientStatus.fromString("foo"))
  }
}
