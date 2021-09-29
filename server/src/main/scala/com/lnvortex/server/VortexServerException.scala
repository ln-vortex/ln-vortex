package com.lnvortex.server

sealed abstract class VortexServerException(private val reason: String)
    extends Exception {
  override def getMessage: String = s"Error: $reason"
}

object VortexServerException {

  case class InvalidInputsException(private val reason: String)
      extends VortexServerException(reason)

  case class InvalidChangeScriptPubKeyException(private val reason: String)
      extends VortexServerException(reason)

  case class NotEnoughFundingException(private val reason: String)
      extends VortexServerException(reason)

  case class InvalidBlindChallengeException(private val reason: String)
      extends VortexServerException(reason)

  case class InvalidOutputSignatureException(private val reason: String)
      extends VortexServerException(reason)

  case class InvalidOutputScriptPubKeyException(private val reason: String)
      extends VortexServerException(reason)

  case class InvalidPSBTSignaturesException(private val reason: String)
      extends VortexServerException(reason)

  case class DifferentTransactionException(private val reason: String)
      extends VortexServerException(reason)

}
