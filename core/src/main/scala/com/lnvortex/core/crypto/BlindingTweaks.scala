package com.lnvortex.core.crypto

import org.bitcoins.crypto._

import scala.annotation.tailrec

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
