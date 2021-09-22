package com.lnvortex.core.crypto

import org.bitcoins.crypto._
import scodec.bits._

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
    // todo check challenge != zero
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
