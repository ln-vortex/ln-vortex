package com.lnvortex.core.crypto

import org.bitcoins.crypto._
import scodec.bits._

import scala.annotation.tailrec

object BlindSchnorrUtil {

  private def computeBlindedHash(
      signerPubKey: SchnorrPublicKey,
      signerNonce: SchnorrNonce,
      blindingTweaks: BlindingTweaks,
      message: ByteVector): ByteVector = {
    val BlindingTweaks(nonceTweak, challengeTweak, keyTweak) = blindingTweaks
    val tweakedNonce = signerNonce.publicKey
      .add(nonceTweak.getPublicKey)
      .add(signerPubKey.publicKey.tweakMultiply(challengeTweak))
      .schnorrNonce
    val tweakedPubKey = {
      if (keyTweak.isZero) signerPubKey
      else {
        signerPubKey.publicKey.add(keyTweak.getPublicKey).schnorrPublicKey
      }
    }

    CryptoUtil
      .sha256SchnorrChallenge(
        tweakedNonce.bytes ++ tweakedPubKey.bytes ++ message)
      .bytes
  }

  def generateChallenge(
      signerPubKey: SchnorrPublicKey,
      signerNonce: SchnorrNonce,
      blindingTweaks: BlindingTweaks,
      message: ByteVector): FieldElement = {
    val hash =
      computeBlindedHash(signerPubKey, signerNonce, blindingTweaks, message)
    FieldElement(hash).add(blindingTweaks.challengeTweak)
  }

  def generateBlindSig(
      privKey: ECPrivateKey,
      nonceKey: ECPrivateKey,
      challenge: FieldElement): FieldElement = {
    privKey.schnorrKey.fieldElement
      .multiply(challenge)
      .add(nonceKey.nonceKey.fieldElement)
  }

  def unblindSignature(
      blindSig: FieldElement,
      signerPubKey: SchnorrPublicKey,
      signerNonce: SchnorrNonce,
      blindingTweaks: BlindingTweaks,
      message: ByteVector): SchnorrDigitalSignature = {
    val BlindingTweaks(nonceTweak, challengeTweak, keyTweak) = blindingTweaks
    val hash = FieldElement(
      computeBlindedHash(signerPubKey, signerNonce, blindingTweaks, message))

    val tweakedSig = blindSig
      .add(nonceTweak)
      .add(hash.multiply(keyTweak))

    val tweakedNonce = signerNonce.publicKey
      .add(nonceTweak.getPublicKey)
      .add(signerPubKey.publicKey.tweakMultiply(challengeTweak))
      .schnorrNonce

    val sig = SchnorrDigitalSignature(tweakedNonce, tweakedSig)

    val tweakedPubKey =
      if (keyTweak.isZero) signerPubKey
      else {
        signerPubKey.publicKey.add(keyTweak.getPublicKey).schnorrPublicKey
      }

    require(
      tweakedPubKey.verify(message, sig),
      s"Blind signature ${blindSig.hex} is invalid for message ${message.toHex}.")

    sig
  }
}

case class BlindingTweaks(
    nonceTweak: FieldElement,
    challengeTweak: FieldElement,
    keyTweak: FieldElement)

object BlindingTweaks {

  private def isValidBIP340Tweak(
      nonTweakedKey: ECPublicKey,
      tweak: FieldElement): Option[FieldElement] = {
    val tweakedKey = nonTweakedKey.add(tweak.getPublicKey)
    if (tweakedKey == tweakedKey.schnorrPublicKey.publicKey) {
      Some(tweak)
    } else {
      None
    }
  }

  @tailrec
  def freshBlindingTweaks(
      signerPubKey: SchnorrPublicKey,
      signerNonce: SchnorrNonce): BlindingTweaks = {
    val nonceTweakRaw = ECPrivateKey.freshPrivateKey.fieldElement
    val challengeTweak = ECPrivateKey.freshPrivateKey.fieldElement

    val tweaksOpt = for {
      nonceTweak <- isValidBIP340Tweak(signerPubKey.publicKey
                                         .tweakMultiply(challengeTweak)
                                         .add(signerNonce.publicKey),
                                       nonceTweakRaw)
    } yield BlindingTweaks(nonceTweak, challengeTweak, FieldElement.zero)

    tweaksOpt match {
      case Some(tweaks) => tweaks
      case None         => freshBlindingTweaks(signerPubKey, signerNonce)
    }
  }

  @tailrec
  def freshBlindingTweaksWithKeyTweak(
      signerPubKey: SchnorrPublicKey,
      signerNonce: SchnorrNonce): BlindingTweaks = {
    val nonceTweakRaw = ECPrivateKey.freshPrivateKey.fieldElement
    val challengeTweak = ECPrivateKey.freshPrivateKey.fieldElement
    val keyTweakRaw = ECPrivateKey.freshPrivateKey.fieldElement

    val tweaksOpt = for {
      keyTweak <- isValidBIP340Tweak(signerPubKey.publicKey, keyTweakRaw)
      nonceTweak <- isValidBIP340Tweak(signerPubKey.publicKey
                                         .tweakMultiply(challengeTweak)
                                         .add(signerNonce.publicKey),
                                       nonceTweakRaw)
    } yield BlindingTweaks(nonceTweak, challengeTweak, keyTweak)

    tweaksOpt match {
      case Some(tweaks) => tweaks
      case None         => freshBlindingTweaksWithKeyTweak(signerPubKey, signerNonce)
    }
  }
}
