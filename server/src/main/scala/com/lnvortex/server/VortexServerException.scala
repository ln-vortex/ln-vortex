package com.lnvortex.server

sealed abstract class VortexServerException(private val reason: String)
    extends Exception {
  override def getMessage: String = s"$reason"
}

object VortexServerException {

  class NonMinimalInputsException(private val reason: String)
      extends VortexServerException(reason)

  class InvalidInputsException(private val reason: String)
      extends VortexServerException(reason)

  class InvalidChangeScriptPubKeyException(private val reason: String)
      extends VortexServerException(reason)

  class NotEnoughFundingException(private val reason: String)
      extends VortexServerException(reason)

  class AttemptedAddressReuseException(private val reason: String)
      extends VortexServerException(reason)

  class InvalidBlindChallengeException(private val reason: String)
      extends VortexServerException(reason)

  class InvalidOutputSignatureException(private val reason: String)
      extends VortexServerException(reason)

  class InvalidTargetOutputScriptPubKeyException(private val reason: String)
      extends VortexServerException(reason)

  class InvalidTargetOutputAmountException(private val reason: String)
      extends VortexServerException(reason)

  class InvalidPSBTSignaturesException(private val reason: String)
      extends VortexServerException(reason)

  class DifferentTransactionException(private val reason: String)
      extends VortexServerException(reason)

}
