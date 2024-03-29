package com.lnvortex.client

sealed abstract class VortexClientException(private val reason: String)
    extends Exception {
  override def getMessage: String = s"Error: $reason"
}

object VortexClientException {

  class InvalidInputException(private val reason: String)
      extends VortexClientException(reason)

  class InvalidTargetOutputException(private val reason: String)
      extends VortexClientException(reason)

  class InvalidChangeOutputException(private val reason: String)
      extends VortexClientException(reason)

  class InvalidInputTypeException(private val reason: String)
      extends VortexClientException(reason)

  class InvalidOutputTypeException(private val reason: String)
      extends VortexClientException(reason)

  class MissingInputsException(private val reason: String)
      extends VortexClientException(reason)

  class DustOutputsException(private val reason: String)
      extends VortexClientException(reason)

  class BadLocktimeException(private val reason: String)
      extends VortexClientException(reason)

  class TooLowOfFeeException(private val reason: String)
      extends VortexClientException(reason)
}
