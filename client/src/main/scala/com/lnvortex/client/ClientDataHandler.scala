package com.lnvortex.client

import akka.actor._
import akka.event.LoggingReceive
import com.lnvortex.core._
import org.bitcoins.core.protocol.tlv._

import scala.concurrent._

class ClientDataHandler(vortexClient: VortexClient, connectionHandler: ActorRef)
    extends Actor
    with ActorLogging {
  implicit val ec: ExecutionContextExecutor = context.system.dispatcher

  override def preStart(): Unit = {
    val _ = context.watch(connectionHandler)
  }

  override def receive: Receive = LoggingReceive {
    case serverMessage: ServerVortexMessage =>
      log.info(s"Received VortexMessage ${serverMessage.typeName}")
      val f: Future[Unit] = handleVortexMessage(serverMessage)
      f.failed.foreach(err =>
        log.error(s"Failed to process vortexMessage=$serverMessage", err))
    case clientMessage: ClientVortexMessage =>
      log.error(s"Received client message $clientMessage")
    case ClientConnectionHandler.WriteFailed(_) =>
      log.error("Write failed")
    case Terminated(actor) if actor == connectionHandler =>
      context.stop(self)
  }

  private def handleVortexMessage(
      message: ServerVortexMessage): Future[Unit] = {
    message match {
      case adv: MixDetails =>
        vortexClient.setRound(adv)
        Future.unit
      case NonceMessage(schnorrNonce) =>
        vortexClient.registerNonce(schnorrNonce)
        Future.unit
      case BlindedSig(blindOutputSig) =>
        for {
          _ <- vortexClient.processBlindOutputSig(blindOutputSig)
        } yield ()
      case UnsignedPsbtMessage(psbt) =>
        for {
          signed <- vortexClient.validateAndSignPsbt(psbt)
          _ = connectionHandler ! SignedPsbtMessage(signed)
        } yield ()
      case SignedTxMessage(transaction) =>
        for {
          _ <- vortexClient.completeRound(transaction)
        } yield ()
    }
  }
}

object ClientDataHandler {

  type Factory = (VortexClient, ActorContext, ActorRef) => ActorRef

  sealed trait Command
  case class Received(tlv: TLV) extends Command
  case class Send(tlv: TLV) extends Command

  def defaultFactory(
      vortexClient: VortexClient,
      context: ActorContext,
      connectionHandler: ActorRef): ActorRef = {
    context.actorOf(props(vortexClient, connectionHandler))
  }

  def props(vortexClient: VortexClient, connectionHandler: ActorRef): Props =
    Props(new ClientDataHandler(vortexClient, connectionHandler))
}
