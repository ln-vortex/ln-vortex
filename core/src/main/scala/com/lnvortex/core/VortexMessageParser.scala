package com.lnvortex.core

import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.util._

object VortexMessageParser extends Logging {

  def parseIndividualMessages(
      bytes: ByteVector): (Vector[VortexMessage], ByteVector) = {
    @tailrec
    def loop(
        remainingBytes: ByteVector,
        accum: Vector[VortexMessage]): (Vector[VortexMessage], ByteVector) = {
      if (remainingBytes.length <= 0) {
        (accum, remainingBytes)
      } else {
        // todo figure out how to properly handle unknown messages
        Try(VortexMessage(remainingBytes)) match {
          case Failure(_) =>
            // If we can't parse the entire message, continue on until we can
            // so we properly skip it
            (accum, remainingBytes)
          case Success(message) =>
            val newRemainingBytes = remainingBytes.drop(message.byteSize)
            logger.debug(
              s"Parsed a message=${message.typeName} from bytes, continuing with remainingBytes=${newRemainingBytes.length}")
            loop(newRemainingBytes, accum :+ message)
        }
      }
    }

    loop(bytes, Vector.empty)
  }
}
