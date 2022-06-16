package com.lnvortex.core.crypto

import org.bitcoins.crypto._
import org.bitcoins.testkitcore.gen._
import org.bitcoins.testkitcore.util.BitcoinSUnitTest
import scodec.bits.ByteVector

class BlindSchnorrUtilTest extends BitcoinSUnitTest {
  behavior of "Blind Schnorr Signing"

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    generatorDrivenConfigNewCode

  it must "successfully complete the protocol" in {
    forAll(CryptoGenerators.privateKey,
           CryptoGenerators.privateKey,
           NumberGenerator.bytevector(32)) {
      case (signerPrivKey, signerNonceKey, message) =>
        val signerPubKey = signerPrivKey.schnorrPublicKey
        val signerNonce = signerNonceKey.schnorrNonce

        val blindingTweaks =
          BlindingTweaks.freshBlindingTweaksWithKeyTweak(signerPubKey,
                                                         signerNonce)

        val challenge = BlindSchnorrUtil.generateChallenge(signerPubKey,
                                                           signerNonce,
                                                           blindingTweaks,
                                                           message)

        val blindSig = BlindSchnorrUtil.generateBlindSig(signerPrivKey,
                                                         signerNonceKey,
                                                         challenge)

        val tweakedPubKey =
          if (blindingTweaks.keyTweak.isZero) signerPubKey
          else {
            signerPubKey.publicKey
              .add(blindingTweaks.keyTweak.getPublicKey)
              .schnorrPublicKey
          }

        val tweakedPrivateKey = signerPrivKey.schnorrKey.fieldElement
          .add(blindingTweaks.keyTweak)
          .toPrivateKey

        assert(tweakedPrivateKey.schnorrPublicKey == tweakedPubKey)

        val tweakedNonceKey = signerNonceKey.nonceKey.fieldElement
          .add(blindingTweaks.nonceTweak)
          .add(signerPrivKey.schnorrKey.fieldElement.multiply(
            blindingTweaks.challengeTweak))
          .toPrivateKey

        val tweakedNonce = signerNonce.publicKey
          .add(blindingTweaks.nonceTweak.getPublicKey)
          .add(signerPubKey.publicKey.multiply(blindingTweaks.challengeTweak))
          .schnorrNonce

        assert(tweakedNonceKey.schnorrNonce == tweakedNonce)

        val expectedChallenge = FieldElement(
          CryptoUtil
            .sha256SchnorrChallenge(
              tweakedNonce.bytes ++ tweakedPubKey.bytes ++ message)
            .bytes).add(blindingTweaks.challengeTweak)

        assert(challenge == expectedChallenge)

        val expectedBlindSig = signerPrivKey.schnorrKey.fieldElement
          .multiply(challenge)
          .add(signerNonceKey.nonceKey.fieldElement)

        assert(blindSig == expectedBlindSig)

        val expectedSig = CryptoUtil.schnorrSignWithNonce(message,
                                                          tweakedPrivateKey,
                                                          tweakedNonceKey)

        assert(tweakedPubKey.verify(message, expectedSig))

        val unblindChallenge =
          challenge.subtract(blindingTweaks.challengeTweak)

        val expectedSig2 = blindSig
          .add(blindingTweaks.nonceTweak)
          .add(unblindChallenge.multiply(blindingTweaks.keyTweak))

        val expectedSigPoint = tweakedPubKey.publicKey
          .multiply(unblindChallenge)
          .add(tweakedNonce.publicKey)

        assert(expectedSig2.getPublicKey == expectedSigPoint)

        val sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                    signerPubKey,
                                                    signerNonce,
                                                    blindingTweaks,
                                                    message)

        assert(sig.sig == expectedSig2)

        assert(sig == expectedSig)

        assert(tweakedPubKey.verify(message, sig))
    }
  }

  it must "successfully complete the protocol when keyTweak is zero" in {
    val signerPrivKey = ECPrivateKey.freshPrivateKey
    val signerNonceKey = ECPrivateKey.freshPrivateKey
    val signerPubKey = signerPrivKey.schnorrPublicKey
    val signerNonce = signerNonceKey.schnorrNonce
    val message = ByteVector.fill(32)(0x01)

    val blindingTweaks =
      BlindingTweaks.freshBlindingTweaks(signerPubKey, signerNonce)

    val challenge = BlindSchnorrUtil.generateChallenge(signerPubKey,
                                                       signerNonce,
                                                       blindingTweaks,
                                                       message)

    val blindSig = BlindSchnorrUtil.generateBlindSig(signerPrivKey,
                                                     signerNonceKey,
                                                     challenge)

    val tweakedNonceKey = signerNonceKey.nonceKey.fieldElement
      .add(blindingTweaks.nonceTweak)
      .add(signerPrivKey.schnorrKey.fieldElement.multiply(
        blindingTweaks.challengeTweak))
      .toPrivateKey

    val expectedSig =
      CryptoUtil.schnorrSignWithNonce(message, signerPrivKey, tweakedNonceKey)

    assert(signerPubKey.verify(message, expectedSig))

    val sig = BlindSchnorrUtil.unblindSignature(blindSig,
                                                signerPubKey,
                                                signerNonce,
                                                blindingTweaks,
                                                message)

    assert(sig == expectedSig)

    assert(signerPubKey.verify(message, sig))
  }

  it must "fail to generate a blind sig when the challenge is zero" in {
    val signerPrivKey = ECPrivateKey.freshPrivateKey
    val signerNonceKey = ECPrivateKey.freshPrivateKey

    assertThrows[IllegalArgumentException](
      BlindSchnorrUtil
        .generateBlindSig(signerPrivKey, signerNonceKey, FieldElement.zero))
  }
}
