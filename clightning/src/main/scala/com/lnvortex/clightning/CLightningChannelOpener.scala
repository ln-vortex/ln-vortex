package com.lnvortex.clightning

import akka.actor.ActorSystem
import com.bitcoins.clightning.rpc.CLightningRpcClient
import com.lnvortex.core.api.OutputDetails
import grizzled.slf4j.Logging
import org.bitcoins.commons.jsonmodels.clightning.CLightningJsonModels._
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.psbt.PSBT

import scala.concurrent._

case class CLightningChannelOpener(clightning: CLightningRpcClient)(implicit
    system: ActorSystem)
    extends Logging {
  implicit val executionContext: ExecutionContext = system.dispatcher

  def initPSBTChannelOpen(
      nodeId: NodeId,
      fundingAmount: CurrencyUnit,
      privateChannel: Boolean): Future[OutputDetails] = {
    clightning
      .initChannelOpen(nodeId, fundingAmount, privateChannel)
      .map { res =>
        OutputDetails(nodeId.bytes, fundingAmount, res.funding_address)
      }
  }

  def completeChannelOpen(
      nodeId: NodeId,
      psbt: PSBT): Future[FundChannelCompleteResult] =
    clightning.completeChannelOpen(nodeId, psbt)

  def cancelChannel(nodeId: NodeId): Future[FundChannelCancelResult] = {
    clightning.cancelChannelOpen(nodeId)
  }
}
