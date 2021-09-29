package com.lnvortex.client

sealed abstract class VortexClientException(private val reason: String)
    extends Exception {
  override def getMessage: String = s"Error: $reason"
}

object VortexClientException {

  case class InvalidMixedOutputException(private val reason: String)
      extends VortexClientException(reason)

  case class InvalidChangeOutputException(private val reason: String)
      extends VortexClientException(reason)

  case class MissingInputsException(private val reason: String)
      extends VortexClientException(reason)

  case class DustOutputsException(private val reason: String)
      extends VortexClientException(reason)
}
