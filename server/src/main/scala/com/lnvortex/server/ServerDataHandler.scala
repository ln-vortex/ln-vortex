package com.lnvortex.server

import akka.actor._
import akka.event.LoggingReceive
import com.lnvortex.core._
import com.lnvortex.server.coordinator.VortexCoordinator
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
      case AskMixDetails(network) =>
        if (coordinator.config.network == network) {
          connectionHandler ! coordinator.mixDetails
          Future.unit
        } else {
          log.warning(
            s"Received AskMixAdvertisement for different network $network")
          Future.unit
        }
      case askNonce: AskNonce =>
        coordinator.getNonce(id, connectionHandler, askNonce).map { msg =>
          connectionHandler ! NonceMessage(msg.nonce)
        }
      case inputs: RegisterInputs =>
        coordinator.registerAlice(id, inputs).map { response =>
          connectionHandler ! response
        }
      case bob: BobMessage =>
        coordinator.verifyAndRegisterBob(bob).map(_ => ())
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
