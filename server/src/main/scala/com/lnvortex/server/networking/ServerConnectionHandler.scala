package com.lnvortex.server.networking

import akka.actor._
import akka.event.LoggingReceive
import akka.io.Tcp
import akka.util.ByteString
import com.lnvortex.core.{VortexMessage, VortexMessageParser}
import com.lnvortex.server.coordinator.VortexCoordinator
import grizzled.slf4j.Logging
import org.bitcoins.crypto.{CryptoUtil, ECPrivateKey}
import scodec.bits.ByteVector

class ServerConnectionHandler(
    coordinator: VortexCoordinator,
    connection: ActorRef,
    dataHandlerFactory: ServerDataHandler.Factory)
    extends Actor
    with ActorLogging {

  import context.dispatcher

  private val id = CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)

  private val handler = dataHandlerFactory(coordinator, id, context, self)

  override def preStart(): Unit = {
    context.watch(connection)
    connection ! Tcp.Register(self)
    connection ! Tcp.ResumeReading
  }

  override def receive: Receive = connected(ByteVector.empty)

  def connected(unalignedBytes: ByteVector): Receive = LoggingReceive {
    case msg: VortexMessage =>
      val byteMessage = ByteString(msg.bytes.toArray)
      connection ! Tcp.Write(byteMessage)
      connection ! Tcp.ResumeReading

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
        VortexMessageParser.parseIndividualMessages(bytes)

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

      handler ! ServerConnectionHandler.WriteFailed(c.cause)
    case ServerConnectionHandler.CloseConnection =>
      connection ! Tcp.Close
    case _: Tcp.ConnectionClosed =>
      coordinator
        .cancelRegistration(Right(id), coordinator.getCurrentRoundId)
        .map { _ =>
          context.stop(self)
        }
      ()
    case Terminated(actor) if actor == connection =>
      coordinator
        .cancelRegistration(Right(id), coordinator.getCurrentRoundId)
        .map { _ =>
          context.stop(self)
        }
      ()
  }
}

object ServerConnectionHandler extends Logging {

  case object CloseConnection
  case class WriteFailed(cause: Option[Throwable])
  case object Ack extends Tcp.Event

  def props(
      coordinator: VortexCoordinator,
      connection: ActorRef,
      dataHandlerFactory: ServerDataHandler.Factory): Props = {
    Props(
      new ServerConnectionHandler(coordinator, connection, dataHandlerFactory))
  }
}
