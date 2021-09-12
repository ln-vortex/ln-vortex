package com.lnvortex.client

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core._
import grizzled.slf4j.Logging
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient

import scala.concurrent.{Future, Promise}

case class VortexClient(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem,
    config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {

  import system.dispatcher

  private var currentRound: Option[MixAdvertisement] = None

  private val handlerP = Promise[ActorRef]()

  private[client] def setRound(adv: MixAdvertisement): Unit = {
    currentRound = Some(adv)
  }

  override def start(): Future[Unit] = {
    val peer: Peer =
      Peer(socket = config.coordinatorAddress,
           socks5ProxyParams = config.socks5ProxyParams)

    for {
      _ <- P2PClient.connect(peer, this, Some(handlerP))
      handler <- handlerP.future
    } yield handler ! AskMixAdvertisement
  }

  override def stop(): Future[Unit] = {
    handlerP.future.map { handler =>
      system.stop(handler)
    }
  }

  def processAliceInitResponse(blindOutputSig: FieldElement): Future[Unit] = {
    println(blindOutputSig)
    Future.unit
  }

  def validateAndSignPsbt(psbt: PSBT): Future[PSBT] = {
    // todo validate psbt has our outputs
    lndRpcClient.finalizePSBT(psbt)
  }

  def completeRound(signedTx: Transaction): Future[Unit] = {
    println(signedTx)
    Future.unit
  }
}
