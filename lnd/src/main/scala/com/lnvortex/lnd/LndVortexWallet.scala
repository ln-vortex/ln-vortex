package com.lnvortex.lnd

import akka.actor.ActorSystem
import com.lnvortex.core.api._
import com.lnvortex.core.{InputReference, UnspentCoin}
import lnrpc.NodeInfoRequest
import org.bitcoins.core.config.{BitcoinNetwork, BitcoinNetworks}
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto.{DoubleSha256DigestBE, SchnorrNonce}
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.LndUtils._
import scodec.bits.ByteVector
import walletrpc.LabelTransactionRequest

import java.net.InetSocketAddress
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

case class LndVortexWallet(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem)
    extends VortexWalletApi {
  import system.dispatcher

  private val channelOpener = LndChannelOpener(lndRpcClient)

  override lazy val network: BitcoinNetwork = {
    val networkF = lndRpcClient.getInfo
      .map(_.chains.head.network)
      .map(BitcoinNetworks.fromString)

    Await.result(networkF, 15.seconds)
  }

  override def getNewAddress(): Future[BitcoinAddress] =
    lndRpcClient.getNewAddress

  override def getChangeAddress(): Future[BitcoinAddress] =
    lndRpcClient.getNewAddress

  override def listCoins(): Future[Vector[UnspentCoin]] = {
    lndRpcClient.listUnspent.map(_.map { utxo =>
      UnspentCoin(utxo.address,
                  utxo.amount,
                  utxo.outPointOpt.get,
                  utxo.confirmations > 0)
    })
  }

  override def createInputProof(
      nonce: SchnorrNonce,
      outputRef: OutputReference): Future[ScriptWitness] = {
    val tx = InputReference.constructInputProofTx(outputRef, nonce)

    for {
      (_, scriptWit) <- lndRpcClient.computeInputScript(tx, 0, outputRef.output)
      _ <- lndRpcClient.leaseOutput(outputRef.outPoint, 3600)
    } yield scriptWit
  }

  override def signPSBT(
      unsigned: PSBT,
      inputs: Vector[OutputReference]): Future[PSBT] = {
    val txOutpoints = unsigned.transaction.inputs.map(_.previousOutput)

    val sigFs = inputs.map { input =>
      val idx = txOutpoints.indexOf(input.outPoint)
      lndRpcClient
        .computeInputScript(unsigned.transaction, idx, input.output)
        .map { case (scriptSig, witness) => (scriptSig, witness, idx) }
    }

    Future.sequence(sigFs).map { sigs =>
      sigs.foldLeft(unsigned) { case (psbt, (scriptSig, witness, idx)) =>
        psbt.addFinalizedScriptWitnessToInput(scriptSig, witness, idx)
      }
    }
  }

  override def broadcastTransaction(transaction: Transaction): Future[Unit] =
    lndRpcClient.publishTransaction(transaction).map(_ => ())

  override def labelTransaction(
      txId: DoubleSha256DigestBE,
      label: String): Future[Unit] = {
    val request = LabelTransactionRequest(txId.bytes, label)
    lndRpcClient.wallet.labelTransaction(request).map(_ => ())
  }

  override def isConnected(nodeId: NodeId): Future[Boolean] = {
    lndRpcClient.isConnected(nodeId)
  }

  override def connect(
      nodeId: NodeId,
      peerAddr: InetSocketAddress): Future[Unit] = {
    lndRpcClient.connectPeer(nodeId, peerAddr).map(_ => ())
  }

  override def initChannelOpen(
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress],
      fundingAmount: CurrencyUnit,
      privateChannel: Boolean): Future[OutputDetails] =
    channelOpener.initPSBTChannelOpen(nodeId = nodeId,
                                      peerAddrOpt = peerAddrOpt,
                                      fundingAmount = fundingAmount,
                                      privateChannel = privateChannel)

  override def completeChannelOpen(
      chanId: ByteVector,
      psbt: PSBT): Future[Unit] = channelOpener.fundPendingChannel(chanId, psbt)

  override def cancelChannel(
      chanOutPoint: TransactionOutPoint,
      nodeId: NodeId): Future[Unit] =
    channelOpener.cancelChannel(chanOutPoint)

  override def start(): Future[Unit] = Future.unit

  override def stop(): Future[Unit] = lndRpcClient.stop().map(_ => ())

  override def listTransactions(): Future[Vector[TransactionDetails]] = {
    lndRpcClient
      .getTransactions(startHeight = 0)
      .map(_.map { tx =>
        TransactionDetails(txId = tx.txId,
                           tx = tx.tx,
                           numConfirmations = tx.numConfirmations,
                           blockHeight = tx.blockHeight,
                           label = tx.label)
      })
  }

  override def listChannels(): Future[Vector[ChannelDetails]] = {
    lndRpcClient
      .listChannels()
      .flatMap { channels =>
        val fs = channels.map { channel =>
          val request = NodeInfoRequest(channel.remotePubkey)
          lndRpcClient.lnd.getNodeInfo(request).map { nodeInfo =>
            ChannelDetails(
              alias = nodeInfo.getNode.alias,
              remotePubkey = NodeId(channel.remotePubkey),
              shortChannelId = ShortChannelId(channel.chanId),
              public = !channel.`private`,
              amount = Satoshis(channel.capacity),
              active = channel.active
            )
          }
        }

        Future.sequence(fs)
      }
  }
}
