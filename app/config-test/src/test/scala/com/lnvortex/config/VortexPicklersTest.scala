package com.lnvortex.config

import com.lnvortex.config.VortexPicklers._
import com.lnvortex.core._
import com.lnvortex.core.gen.Generators
import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class VortexPicklersTest extends BitcoinSUnitTest {

  it must "serialize a UnspentCoin" in {
    forAll(Generators.unspentCoin) { utxo =>
      val json = upickle.default.writeJs(utxo)
      val parsed = upickle.default.read[UnspentCoin](json)

      assert(parsed == utxo)
    }
  }
}
