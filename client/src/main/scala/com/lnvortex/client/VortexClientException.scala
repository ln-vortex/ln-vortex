package com.lnvortex.client

sealed abstract class VortexClientException(private val reason: String)
    extends Exception {
  override def getMessage: String = s"Error: $reason"
}

object VortexClientException {

  class InvalidMixedOutputException(private val reason: String)
      extends VortexClientException(reason)

  class InvalidChangeOutputException(private val reason: String)
      extends VortexClientException(reason)

  class MissingInputsException(private val reason: String)
      extends VortexClientException(reason)

  class DustOutputsException(private val reason: String)
      extends VortexClientException(reason)
}
