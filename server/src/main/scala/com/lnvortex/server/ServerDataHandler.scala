package com.lnvortex.server

import akka.actor._
import akka.event.LoggingReceive
import com.lnvortex.core._
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.crypto.{CryptoUtil, ECPrivateKey, Sha256Digest}

import scala.concurrent._

class ServerDataHandler(
    coordinator: VortexCoordinator,
    connectionHandler: ActorRef)
    extends Actor
    with ActorLogging {
  implicit val ec: ExecutionContextExecutor = context.system.dispatcher

  private val id: Sha256Digest =
    CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)

  override def preStart(): Unit = {
    val _ = context.watch(connectionHandler)
  }

  override def receive: Receive = LoggingReceive {
    case clientMessage: ClientVortexMessage =>
      log.info(s"Received VortexMessage ${clientMessage.typeName}")
      val f: Future[Unit] = handleVortexMessage(clientMessage)
      f.failed.foreach(err =>
        log.error(s"Failed to process vortexMessage=$clientMessage", err))
    case clientMessage: ServerVortexMessage =>
      log.error(s"Received server message $clientMessage")
    case ServerConnectionHandler.WriteFailed(_) =>
      log.error("Write failed")
    case Terminated(actor) if actor == connectionHandler =>
      context.stop(self)
  }

  private def handleVortexMessage(
      message: ClientVortexMessage): Future[Unit] = {
    message match {
      case AskMixAdvertisement(network) =>
        if (coordinator.config.network == network) {
          val adv = coordinator.getAdvertisement(id, connectionHandler)
          connectionHandler ! adv
          Future.unit
        } else {
          log.warning(
            s"Received AskMixAdvertisement for different network $network")
          Future.unit
        }
      case init: AliceInit =>
        val response = coordinator.registerAlice(id, init)
        connectionHandler ! response

        Future.unit
      case bob: BobMessage =>
        val _ = coordinator.verifyAndRegisterBob(bob)
        // todo queue up for unsigned psbt
        Future.unit
      case SignedPsbtMessage(psbt) =>
        coordinator.registerPSBTSignature(id, psbt).map { signedTx =>
          connectionHandler ! SignedTxMessage(signedTx)
        }
    }
  }
}

object ServerDataHandler {

  type Factory = (VortexCoordinator, ActorContext, ActorRef) => ActorRef

  sealed trait Command
  case class Received(tlv: TLV) extends Command
  case class Send(tlv: TLV) extends Command

  def defaultFactory(
      vortexClient: VortexCoordinator,
      context: ActorContext,
      connectionHandler: ActorRef): ActorRef = {
    context.actorOf(props(vortexClient, connectionHandler))
  }

  def props(
      vortexClient: VortexCoordinator,
      connectionHandler: ActorRef): Props =
    Props(new ServerDataHandler(vortexClient, connectionHandler))
}
