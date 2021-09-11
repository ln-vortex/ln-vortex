package com.lnvortex.server

import akka.actor._
import akka.event.LoggingReceive
import akka.io.Tcp
import akka.util.ByteString
import org.bitcoins.core.api.dlc.wallet.DLCWalletApi
import org.bitcoins.core.protocol.tlv._
import scodec.bits.ByteVector

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

class ServerConnectionHandler(
    dlcWalletApi: DLCWalletApi,
    connection: ActorRef,
    handlerP: Option[Promise[ActorRef]],
    dataHandlerFactory: DLCDataHandler.Factory)
    extends Actor
    with ActorLogging {

  private val handler = {
    val h = dataHandlerFactory(dlcWalletApi, context, self)
    handlerP.foreach(_.success(h))
    h
  }

  override def preStart(): Unit = {
    context.watch(connection)
    connection ! Tcp.Register(self)
    connection ! Tcp.ResumeReading
  }

  override def receive: Receive = connected(ByteVector.empty)

  def connected(unalignedBytes: ByteVector): Receive = LoggingReceive {
    case lnMessage: LnMessage[TLV] =>
      val byteMessage = ByteString(lnMessage.bytes.toArray)
      connection ! Tcp.Write(byteMessage)
      connection ! Tcp.ResumeReading

    case tlv: TLV =>
      Try(LnMessage[TLV](tlv)) match {
        case Success(message) => self.forward(message)
        case Failure(ex) =>
          ex.printStackTrace()
          log.error(s"Cannot send message", ex)
      }

    case Tcp.Received(data) =>
      val byteVec = ByteVector(data.toArray)
      log.debug(s"Received ${byteVec.length} TCP bytes")
      log.debug(s"Received TCP bytes: ${byteVec.toHex}")
      log.debug {
        val post =
          if (unalignedBytes.isEmpty) "None"
          else unalignedBytes.toHex
        s"Unaligned bytes: $post"
      }

      if (unalignedBytes.isEmpty) {
        connection ! Tcp.ResumeReading
      }

      //we need to aggregate our previous 'unalignedBytes' with the new message
      //we just received from our peer to hopefully be able to parse full messages
      val bytes: ByteVector = unalignedBytes ++ byteVec
      log.debug(s"Bytes for message parsing: ${bytes.toHex}")
      val (messages, newUnalignedBytes) =
        ClientConnectionHandler.parseIndividualMessages(bytes)

      log.debug {
        val length = messages.length
        val suffix = if (length == 0) "" else s": ${messages.mkString(", ")}"

        s"Parsed $length message(s) from bytes$suffix"
      }
      log.debug(s"Unaligned bytes after this: ${newUnalignedBytes.length}")
      if (newUnalignedBytes.nonEmpty) {
        log.debug(s"Unaligned bytes: ${newUnalignedBytes.toHex}")
      }

      messages.foreach(m => handler ! m)

      connection ! Tcp.ResumeReading
      context.become(connected(newUnalignedBytes))

    case Tcp.PeerClosed => context.stop(self)

    case c @ Tcp.CommandFailed(_: Tcp.Write) =>
      // O/S buffer was full
      val errorMessage = "Cannot write bytes "
      c.cause match {
        case Some(ex) => log.error(errorMessage, ex)
        case None     => log.error(errorMessage)
      }

      handler ! ClientConnectionHandler.WriteFailed(c.cause)
    case ClientConnectionHandler.CloseConnection =>
      connection ! Tcp.Close
    case _: Tcp.ConnectionClosed =>
      context.stop(self)
    case Terminated(actor) if actor == connection =>
      context.stop(self)
  }
}
