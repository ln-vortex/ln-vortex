package com.lnvortex.server.networking

import akka.actor._
import akka.event.LoggingReceive
import com.lnvortex.core._
import com.lnvortex.server.coordinator.VortexCoordinator
import grizzled.slf4j.Logging
import org.bitcoins.crypto._
import scodec.bits.ByteVector

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent._
import scala.concurrent.duration.DurationInt

class ServerDataHandler(
    coordinator: VortexCoordinator,
    connectionHandler: ActorRef)
    extends Actor
    with Logging {
  implicit val ec: ExecutionContextExecutor = context.system.dispatcher

  private val id: Sha256Digest =
    CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)

  override def preStart(): Unit = {
    val _ = context.watch(connectionHandler)
  }

  val lastPing = new AtomicReference(ByteVector.empty.toArray)

  context.system.scheduler.scheduleAtFixedRate(0.seconds, 60.seconds) { () =>
    val hash = CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)
    val ping = PingTLV(hash.bytes)
    lastPing.set(hash.bytes.toArray)
    connectionHandler ! ping
  }

  override def receive: Receive = LoggingReceive {
    case ping: PingTLV =>
      val pong = PongTLV.forIgnored(ping.ignored)
      connectionHandler ! pong
    case pongTLV: PongTLV =>
      if (!(pongTLV.ignored.toArray sameElements lastPing.get())) {
        logger.error("Received invalid pong message")
      }
    case clientMessage: ClientVortexMessage =>
      logger.debug(s"Received VortexMessage ${clientMessage.typeName}")
      val f: Future[Unit] = handleVortexMessage(clientMessage)
      f.failed.foreach { err =>
        logger.error(s"Failed to process vortexMessage=$clientMessage", err)
      }
    case clientMessage: ServerVortexMessage =>
      logger.error(s"Received server message $clientMessage")
    case unknown: UnknownVortexMessage =>
      logger.warn(s"Received unknown message $unknown")
    case ServerConnectionHandler.WriteFailed(_) =>
      logger.error("Write failed")
    case Terminated(actor) if actor == connectionHandler =>
      context.stop(self)
  }

  private def handleVortexMessage(
      message: ClientVortexMessage): Future[Unit] = {
    message match {
      case AskMixDetails(network) =>
        val currentNetwork = coordinator.config.network
        if (currentNetwork == network) {
          connectionHandler ! coordinator.mixDetails
          Future.unit
        } else {
          logger.warn(
            s"Received AskMixDetails for different network $network, current network $currentNetwork")
          Future.unit
        }
      case askNonce: AskNonce =>
        coordinator.getNonce(id, connectionHandler, askNonce).map { msg =>
          connectionHandler ! NonceMessage(msg.nonce)
        }
      case inputs: RegisterInputs =>
        for {
          _ <- coordinator.registerAlice(id, inputs)
        } yield ()
      case bob: RegisterMixOutput =>
        coordinator.verifyAndRegisterBob(bob).map(_ => ())
      case SignedPsbtMessage(psbt) =>
        coordinator.registerPSBTSignatures(id, psbt).map { signedTx =>
          connectionHandler ! SignedTxMessage(signedTx)
        }
      case cancel: CancelRegistrationMessage =>
        coordinator.cancelRegistration(cancel.nonce, cancel.roundId)
    }
  }
}

object ServerDataHandler {

  type Factory = (VortexCoordinator, ActorContext, ActorRef) => ActorRef

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
