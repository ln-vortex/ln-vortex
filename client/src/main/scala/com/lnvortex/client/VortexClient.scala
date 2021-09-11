package com.lnvortex.client

import akka.actor.ActorSystem
import com.lnvortex.core._
import grizzled.slf4j.Logging
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient

import scala.concurrent.Future

case class VortexClient(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem,
    config: VortexClient)
    extends StartStopAsync[Unit]
    with Logging {

  private var currentRound: Option[MixAdvertisement] = None

  private[client] def setRound(adv: MixAdvertisement): VortexClient = {
    currentRound = Some(adv)
    this
  }

  override def start(): Future[Unit] = Future.unit

  override def stop(): Future[Unit] = Future.unit

  def processAliceInitResponse(blindOutputSig: FieldElement): Future[Unit] = {}

  def validateAndSignPsbt(psbt: PSBT): Future[PSBT] = {
    // todo validate psbt has our outputs
    lndRpcClient.finalizePSBT(psbt)
  }

  def completeRound(signedTx: Transaction): Future[Unit] = {}
}
