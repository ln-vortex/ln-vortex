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

  def isInitialized: Boolean
  def isSigned: Boolean
}

case class Advertised(id: Sha256Digest, connectionHandler: ActorRef)
    extends ClientDetails {
  override val isInitialized: Boolean = false
  override val isSigned: Boolean = false

  def toNonceGiven(nonce: SchnorrNonce, noncePath: BIP32Path): NonceGiven = {
    NonceGiven(id, connectionHandler, nonce, noncePath)
  }
}

sealed trait ClientDetailsWithNonce {
  def id: Sha256Digest
  def connectionHandler: ActorRef
  def nonce: SchnorrNonce
  def noncePath: BIP32Path

  def isInitialized: Boolean
  def isSigned: Boolean
}

case class NonceGiven(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path
) extends ClientDetailsWithNonce {
  override val isInitialized: Boolean = false
  override val isSigned: Boolean = false

  def toInitialized(registerInputs: RegisterInputs): Initialized = {
    Initialized(id, connectionHandler, nonce, noncePath, registerInputs)
  }
}

case class Initialized(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path,
    registerInputs: RegisterInputs)
    extends ClientDetails {
  override val isInitialized: Boolean = true
  override val isSigned: Boolean = false

  def toUnsigned(
      unsignedPSBT: PSBT,
      signedP: Promise[PSBT],
      indexes: Vector[Int]): Unsigned = {
    Unsigned(
      id = id,
      connectionHandler = connectionHandler,
      nonce = nonce,
      noncePath = noncePath,
      registerInputs = registerInputs,
      unsignedPSBT = unsignedPSBT,
      signedP = signedP,
      indexes = indexes
    )
  }
}

sealed trait ReadyToSign extends ClientDetails {
  def signedP: Promise[PSBT]
  def indexes: Vector[Int]
}

case class Unsigned(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path,
    registerInputs: RegisterInputs,
    unsignedPSBT: PSBT,
    signedP: Promise[PSBT],
    indexes: Vector[Int])
    extends ReadyToSign {
  override val isInitialized: Boolean = true
  override val isSigned: Boolean = false

  require(registerInputs.inputs.size == indexes.size)

  def toSigned(psbt: PSBT): Signed = {
    Signed(
      id = id,
      connectionHandler = connectionHandler,
      nonce = nonce,
      noncePath = noncePath,
      registerInputs = registerInputs,
      signedP = signedP.success(psbt),
      indexes = indexes,
      signedPSBT = psbt
    )
  }
}

case class Signed(
    id: Sha256Digest,
    connectionHandler: ActorRef,
    nonce: SchnorrNonce,
    noncePath: BIP32Path,
    registerInputs: RegisterInputs,
    signedP: Promise[PSBT],
    indexes: Vector[Int],
    signedPSBT: PSBT)
    extends ReadyToSign {
  override val isInitialized: Boolean = true
  override val isSigned: Boolean = true
}
