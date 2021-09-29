package com.lnvortex.client.api

import com.lnvortex.client.OutputDetails
import com.lnvortex.core._
import org.bitcoins.core.config.BitcoinNetwork
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.crypto.SchnorrNonce
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import scala.concurrent._

abstract class CoinJoinWalletApi extends StartStopAsync[Unit] {

  def network: BitcoinNetwork

  def getNewAddress: Future[BitcoinAddress]

  def getChangeAddress: Future[BitcoinAddress]

  def listCoins: Future[Vector[UnspentCoin]]

  /** Creates a proof of ownership for the input and then locks it
    *
    * @param nonce     Round Nonce for the peer
    * @param outputRef OutputReference for the input
    * @return Signed ScriptWitness
    */
  def createInputProof(
      nonce: SchnorrNonce,
      outputRef: OutputReference): Future[ScriptWitness]

  def signPSBT(psbt: PSBT, inputs: Vector[OutputReference]): Future[PSBT]

  def broadcastTransaction(transaction: Transaction): Future[Unit]

  def initChannelOpen(
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress],
      fundingAmount: CurrencyUnit,
      privateChannel: Boolean): Future[OutputDetails]

  def completeChannelOpen(chanId: ByteVector, psbt: PSBT): Future[Unit]

  def cancelChannel(chanOutPoint: TransactionOutPoint): Future[Unit]
}
