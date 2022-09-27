package com.lnvortex.core

import com.lnvortex.core.gen.Generators
import org.bitcoins.testkitcore.util.BitcoinSUnitTest
import play.api.libs.json._

class VortexMessageSerializationTest extends BitcoinSUnitTest {

  "RoundParameters" must "have serialization symmetry" in {
    forAll(Generators.roundParameters) { msg =>
      assert(Json.toJson(msg).as[RoundParameters] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "AskInputs" must "have serialization symmetry" in {
    forAll(Generators.askInputs) { msg =>
      assert(Json.toJson(msg).as[AskInputs] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "Nonce" must "have serialization symmetry" in {
    forAll(Generators.nonceMsg) { msg =>
      assert(Json.toJson(msg).as[NonceMessage] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "InputReference" must "have serialization symmetry" in {
    forAll(Generators.inputReference) { msg =>
      assert(Json.toJson(msg).as[InputReference] == msg)
    }
  }

  "RegisterInputs" must "have serialization symmetry" in {
    forAll(Generators.registerInputs) { msg =>
      assert(Json.toJson(msg).as[RegisterInputs] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "BlindedSig" must "have serialization symmetry" in {
    forAll(Generators.blindedSig) { msg =>
      assert(Json.toJson(msg).as[BlindedSig] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "RegisterOutput" must "have serialization symmetry" in {
    forAll(Generators.registerOutput) { msg =>
      assert(Json.toJson(msg).as[RegisterOutput] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "UnsignedPsbtMessage" must "have serialization symmetry" in {
    forAll(Generators.unsignedPsbtMessage) { msg =>
      assert(Json.toJson(msg).as[UnsignedPsbtMessage] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "SignedPsbtMessage" must "have serialization symmetry" in {
    forAll(Generators.signedPsbtMessage) { msg =>
      assert(Json.toJson(msg).as[SignedPsbtMessage] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "SignedTxMessage" must "have serialization symmetry" in {
    forAll(Generators.signedTxMessage) { msg =>
      assert(Json.toJson(msg).as[SignedTxMessage] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "RestartRoundMessage" must "have serialization symmetry" in {
    forAll(Generators.restartRoundMessage) { msg =>
      assert(Json.toJson(msg).as[RestartRoundMessage] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "CancelRegistrationMessage" must "have serialization symmetry" in {
    forAll(Generators.cancelRegistrationMessage) { msg =>
      assert(Json.toJson(msg).as[CancelRegistrationMessage] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "FeeRateHint" must "have serialization symmetry" in {
    forAll(Generators.feeRateHint) { msg =>
      assert(Json.toJson(msg).as[FeeRateHint] == msg)
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  "VortexMessage" must "have serialization symmetry" in {
    forAll(Generators.vortexMessage) { msg =>
      assert(Json.toJson(msg).as[VortexMessage] == msg)
    }
  }

  it must "parse the static test vector" in {
    val json = Json.parse(getClass.getResourceAsStream("/vortex_messages.json"))
    val messages = json.as[Vector[VortexMessage]]
    assert(messages.size == 1000)
  }
}
