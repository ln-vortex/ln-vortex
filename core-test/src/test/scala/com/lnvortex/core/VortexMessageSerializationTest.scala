package com.lnvortex.core

import com.lnvortex.core.gen.Generators
import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class VortexMessageSerializationTest extends BitcoinSUnitTest {

  it must "have unique types" in {
    val allTypes = VortexMessage.allFactories.map(_.tpe)
    assert(allTypes.distinct == allTypes)
  }

  "Ping" must "have serialization symmetry" in {
    val msg = PingTLV()
    assert(PingTLV(msg.bytes) == msg)
    assert(VortexMessage(msg.bytes) == msg)
  }

  "Pong" must "have serialization symmetry" in {
    val msg = PongTLV()
    assert(PongTLV(msg.bytes) == msg)
    assert(VortexMessage(msg.bytes) == msg)
  }

  "AskMixDetails" must "have serialization symmetry" in {
    forAll(Generators.askMixDetails) { msg =>
      assert(AskMixDetails(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "MixDetails" must "have serialization symmetry" in {
    forAll(Generators.mixDetails) { msg =>
      assert(MixDetails(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "AskNonce" must "have serialization symmetry" in {
    forAll(Generators.askNonce) { msg =>
      assert(AskNonce(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "AskInputs" must "have serialization symmetry" in {
    forAll(Generators.askInputs) { msg =>
      assert(AskInputs(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "Nonce" must "have serialization symmetry" in {
    forAll(Generators.nonceMsg) { msg =>
      assert(NonceMessage(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "InputReference" must "have serialization symmetry" in {
    forAll(Generators.inputReference) { msg =>
      assert(InputReference(msg.bytes) == msg)
    }
  }

  "RegisterInputs" must "have serialization symmetry" in {
    forAll(Generators.registerInputs) { msg =>
      assert(RegisterInputs(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "BlindedSig" must "have serialization symmetry" in {
    forAll(Generators.blindedSig) { msg =>
      assert(BlindedSig(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "RegisterMixOutput" must "have serialization symmetry" in {
    forAll(Generators.registerMixOutput) { msg =>
      assert(RegisterMixOutput(msg.bytes) == msg)
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

  "RestartRoundMessage" must "have serialization symmetry" in {
    forAll(Generators.restartRoundMessage) { msg =>
      assert(RestartRoundMessage(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }

  "CancelRegistrationMessage" must "have serialization symmetry" in {
    forAll(Generators.cancelRegistrationMessage) { msg =>
      assert(CancelRegistrationMessage(msg.bytes) == msg)
      assert(VortexMessage(msg.bytes) == msg)
    }
  }
}
