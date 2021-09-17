package com.lnvortex.core

import com.lnvortex.core.gen.Generators
import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class VortexMessageSerializationTest extends BitcoinSUnitTest {

  it must "have unique types" in {
    val allTypes = VortexMessage.allFactories.map(_.tpe)
    assert(allTypes.distinct == allTypes)
  }

  "AskMixAdvertisement" must "have serialization symmetry" in {
    forAll(Generators.askMixAdvertisement) { msg =>
      assert(AskMixAdvertisement(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "MixAdvertisement" must "have serialization symmetry" in {
    forAll(Generators.mixAdvertisement) { msg =>
      assert(MixAdvertisement(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "InputReference" must "have serialization symmetry" in {
    forAll(Generators.inputReference) { msg =>
      assert(InputReference(msg.bytes) == msg)
    }
  }

  "AliceInit" must "have serialization symmetry" in {
    forAll(Generators.aliceInit) { msg =>
      assert(AliceInit(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "AliceInitResponse" must "have serialization symmetry" in {
    forAll(Generators.aliceInitResponse) { msg =>
      assert(AliceInitResponse(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "BobMessage" must "have serialization symmetry" in {
    forAll(Generators.bobMessage) { msg =>
      assert(BobMessage(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "UnsignedPsbtMessage" must "have serialization symmetry" in {
    forAll(Generators.unsignedPsbtMessage) { msg =>
      assert(UnsignedPsbtMessage(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "SignedPsbtMessage" must "have serialization symmetry" in {
    forAll(Generators.signedPsbtMessage) { msg =>
      assert(SignedPsbtMessage(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "SignedTxMessage" must "have serialization symmetry" in {
    forAll(Generators.signedTxMessage) { msg =>
      assert(SignedTxMessage(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }
}
