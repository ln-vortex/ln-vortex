package com.lnvortex.core.api

import com.lnvortex.core._
import org.bitcoins.core.config.BitcoinNetwork
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.crypto._
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import scala.concurrent._

abstract class VortexWalletApi extends StartStopAsync[Unit] {

  def network: BitcoinNetwork

  def getBlockHeight(): Future[Int]

  def getNewAddress(scriptType: ScriptType): Future[BitcoinAddress]

  def getChangeAddress(scriptType: ScriptType): Future[BitcoinAddress]

  def listCoins(): Future[Vector[UnspentCoin]]

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

  def labelTransaction(txId: DoubleSha256Digest, label: String): Future[Unit]

  def isConnected(nodeId: NodeId): Future[Boolean]

  def connect(nodeId: NodeId, peerAddr: InetSocketAddress): Future[Unit]

  def initChannelOpen(
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress],
      fundingAmount: CurrencyUnit,
      privateChannel: Boolean): Future[OutputDetails]

  def completeChannelOpen(chanId: ByteVector, psbt: PSBT): Future[Unit]

  def cancelPendingChannel(chanId: ByteVector): Future[Unit]

  def cancelChannel(
      chanOutPoint: TransactionOutPoint,
      nodeId: NodeId): Future[Unit]

  def listTransactions(): Future[Vector[TransactionDetails]]

  def listChannels(): Future[Vector[ChannelDetails]]
}
