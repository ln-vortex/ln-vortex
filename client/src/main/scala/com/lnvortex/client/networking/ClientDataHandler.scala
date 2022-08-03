package com.lnvortex.client.networking

import akka.actor._
import akka.event.LoggingReceive
import com.lnvortex.client.VortexClient
import com.lnvortex.core._
import com.lnvortex.core.api.VortexWalletApi
import grizzled.slf4j.Logging

import scala.concurrent._
import scala.concurrent.duration.DurationInt

class ClientDataHandler(
    vortexClient: VortexClient[VortexWalletApi],
    connectionHandler: ActorRef)
    extends Actor
    with Logging {
  implicit val ec: ExecutionContextExecutor = context.system.dispatcher

  override def preStart(): Unit = {
    val _ = context.watch(connectionHandler)
  }

  private val cancellable: Cancellable =
    context.system.scheduler.scheduleAtFixedRate(30.seconds, 60.seconds) { () =>
      val ping = PingTLV()
      connectionHandler ! ping
    }

  override def receive: Receive = LoggingReceive {
    case _: PingTLV =>
      val pong = PongTLV()
      connectionHandler ! pong
    case _: PongTLV => ()
    case serverMessage: ServerVortexMessage =>
      logger.debug(s"Received VortexMessage ${serverMessage.typeName}")
      val f: Future[Unit] = handleVortexMessage(serverMessage)
      f.failed.foreach { err =>
        logger.error(s"Failed to process vortexMessage=$serverMessage", err)
      }
    case clientMessage: ClientVortexMessage =>
      logger.error(s"Received client message $clientMessage")
    case unknown: UnknownVortexMessage =>
      logger.warn(s"Received unknown message $unknown")
    case ClientConnectionHandler.WriteFailed(_) =>
      logger.error("Write failed")
    case Terminated(actor) if actor == connectionHandler =>
      cancellable.cancel()
      context.stop(self)
  }

  private def handleVortexMessage(
      message: ServerVortexMessage): Future[Unit] = {
    message match {
      case adv: RoundParameters =>
        logger.info(s"Received round details ${adv.roundId.hex}")
        vortexClient.setRound(adv)
        Future.unit
      case NonceMessage(schnorrNonce) =>
        vortexClient.storeNonce(schnorrNonce)
        Future.unit
      case AskInputs(roundId, inputFee, outputFee, changeOutputFee) =>
        logger.info("Received AskInputs from coordinator")
        vortexClient
          .registerCoins(roundId, inputFee, outputFee, changeOutputFee)
          .map { registerInputs =>
            connectionHandler ! registerInputs
            logger.info("Sent RegisterInputs to coordinator")
          }
      case BlindedSig(blindOutputSig) =>
        val msg = vortexClient.processBlindOutputSig(blindOutputSig)
        vortexClient.sendOutputMessageAsBob(msg)
      case UnsignedPsbtMessage(psbt) =>
        for {
          signed <- vortexClient.validateAndSignPsbt(psbt)
          _ = connectionHandler ! SignedPsbtMessage(signed)
        } yield ()
      case SignedTxMessage(transaction) =>
        for {
          _ <- vortexClient.completeRound(transaction)
        } yield ()
      case msg: RestartRoundMessage =>
        vortexClient.restartRound(msg)
        Future.unit
    }
  }

  override def postStop(): Unit = {
    cancellable.cancel()
    ()
  }
}

object ClientDataHandler {

  type Factory =
    (VortexClient[VortexWalletApi], ActorContext, ActorRef) => ActorRef

  sealed trait Command

  def defaultFactory(
      vortexClient: VortexClient[VortexWalletApi],
      context: ActorContext,
      connectionHandler: ActorRef): ActorRef = {
    context.actorOf(props(vortexClient, connectionHandler))
  }

  def props(
      vortexClient: VortexClient[VortexWalletApi],
      connectionHandler: ActorRef): Props =
    Props(new ClientDataHandler(vortexClient, connectionHandler))
}
