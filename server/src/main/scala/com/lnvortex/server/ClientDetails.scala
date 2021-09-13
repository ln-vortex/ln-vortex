package com.lnvortex.server

import akka.actor.ActorRef
import com.lnvortex.core._
import org.bitcoins.core.hd.BIP32Path
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._

import scala.concurrent.Promise

sealed trait ClientDetails {
  def id: Sha256Digest
  def connectionHandler: ActorRef
  def nonce: SchnorrNonce
  def noncePath: BIP32Path

  def initialized: Boolean
  def signed: Boolean
}

case class Advertised(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path)
    extends ClientDetails {
  override val initialized: Boolean = false
  override val signed: Boolean = false

  def toInitialized(aliceInit: AliceInit): Initialized = {
    Initialized(id, connectionHandler, nonce, noncePath, aliceInit)
  }
}

case class Initialized(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path,
    aliceInit: AliceInit)
    extends ClientDetails {
  override val initialized: Boolean = true
  override val signed: Boolean = false

  def toUnsigned(signedP: Promise[PSBT], indexes: Vector[Int]): Unsigned = {
    Unsigned(id = id,
             connectionHandler = connectionHandler,
             nonce = nonce,
             noncePath = noncePath,
             aliceInit = aliceInit,
             signedP = signedP,
             indexes = indexes)
  }
}

case class Unsigned(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path,
    aliceInit: AliceInit,
    signedP: Promise[PSBT],
    indexes: Vector[Int])
    extends ClientDetails {
  override val initialized: Boolean = true
  override val signed: Boolean = false

  def toSigned(psbt: PSBT): Signed = {
    Signed(id = id,
           connectionHandler = connectionHandler,
           nonce = nonce,
           noncePath = noncePath,
           aliceInit = aliceInit,
           signedP = signedP,
           indexes = indexes,
           signedPSBT = psbt)
  }
}

case class Signed(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path,
    aliceInit: AliceInit,
    signedP: Promise[PSBT],
    indexes: Vector[Int],
    signedPSBT: PSBT)
    extends ClientDetails {
  override val initialized: Boolean = true
  override val signed: Boolean = true
}
