package com.lnvortex.clightning

import akka.actor.ActorSystem
import com.bitcoins.clightning.rpc.CLightningRpcClient
import com.lnvortex.core.api._
import com.lnvortex.core.{InputReference, UnspentCoin}
import org.bitcoins.core.config._
import org.bitcoins.core.currency._
import org.bitcoins.core.hd.AddressType
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import scala.concurrent._
import scala.util.Try

case class CLightningVortexWallet(clightning: CLightningRpcClient)(implicit
    system: ActorSystem)
    extends VortexWalletApi {
  implicit val executionContext: ExecutionContext = system.dispatcher

  private val channelOpener = CLightningChannelOpener(clightning)

  override lazy val network: BitcoinNetwork =
    clightning.instance.network

  override def getBlockHeight(): Future[Int] = {
    clightning.getInfo.map(_.blockheight)
  }

  private def addressTypeFromScriptType(scriptType: ScriptType): AddressType = {
    scriptType match {
      case ScriptType.PUBKEY | ScriptType.NONSTANDARD | ScriptType.MULTISIG |
          ScriptType.CLTV | ScriptType.CSV |
          ScriptType.NONSTANDARD_IF_CONDITIONAL |
          ScriptType.NOT_IF_CONDITIONAL | ScriptType.MULTISIG_WITH_TIMEOUT |
          ScriptType.PUBKEY_WITH_TIMEOUT | ScriptType.NULLDATA |
          ScriptType.WITNESS_UNKNOWN | ScriptType.WITNESS_COMMITMENT =>
        throw new IllegalArgumentException("Unknown address type")
      case ScriptType.PUBKEYHASH         => AddressType.Legacy
      case ScriptType.SCRIPTHASH         => AddressType.NestedSegWit
      case ScriptType.WITNESS_V0_KEYHASH => AddressType.SegWit
      case ScriptType.WITNESS_V0_SCRIPTHASH =>
        throw new IllegalArgumentException("Unknown address type")
      case ScriptType.WITNESS_V1_TAPROOT =>
        throw new IllegalArgumentException("Waiting on 0.15-beta")
    }
  }

  override def getNewAddress(scriptType: ScriptType): Future[BitcoinAddress] = {
    val addressType = addressTypeFromScriptType(scriptType)
    clightning.getNewAddress(addressType)
  }

  override def getChangeAddress(
      scriptType: ScriptType): Future[BitcoinAddress] = {
    val addressType = addressTypeFromScriptType(scriptType)
    clightning.getNewAddress(addressType)
  }

  override def listCoins(): Future[Vector[UnspentCoin]] = {
    val outsF = clightning.listFunds
    outsF.map(_.outputs.map { out =>
      UnspentCoin(address = out.address.get,
                  amount = out.value,
                  outPoint = out.outPoint,
                  confirmed = out.blockheight.isDefined)
    })
  }

  override def createInputProof(
      nonce: SchnorrNonce,
      outputRef: OutputReference): Future[ScriptWitness] = {
    val tx = InputReference.constructInputProofTx(outputRef, nonce)
    val psbt = PSBT
      .fromUnsignedTx(tx)
      .addWitnessUTXOToInput(outputRef.output, 0)

    for {
      _ <- clightning.reserveInputs(psbt, exclusive = false, reserve = 100)
      signed <- clightning.signPSBT(psbt)
      finalized <- Future.fromTry(signed.finalizeInput(0))
    } yield finalized.inputMaps.head.finalizedScriptWitnessOpt.get.scriptWitness
  }

  override def signPSBT(
      unsigned: PSBT,
      outputRefs: Vector[OutputReference]): Future[PSBT] = {
    // make sure we signed correct inputs
    val indexesToSign = outputRefs.map { outputRef =>
      val index = unsigned.transaction.inputs.indexWhere(
        _.previousOutput == outputRef.outPoint)
      index
    }

    for {
      _ <- clightning.reserveInputs(unsigned, exclusive = false, reserve = 100)
      signed <- clightning.signPSBT(unsigned, indexesToSign)
      finalizedT = indexesToSign.foldLeft(Try(signed)) { case (psbtT, idx) =>
        psbtT.flatMap(_.finalizeInput(idx))
      }
      finalized <- Future.fromTry(finalizedT)
    } yield finalized
  }

  override def broadcastTransaction(transaction: Transaction): Future[Unit] =
    Future.unit

  override def labelTransaction(
      txId: DoubleSha256Digest,
      label: String): Future[Unit] = Future.unit

  override def isConnected(nodeId: NodeId): Future[Boolean] = {
    clightning.isConnected(nodeId)
  }

  override def connect(
      nodeId: NodeId,
      peerAddr: InetSocketAddress): Future[Unit] = {
    clightning.connect(nodeId, peerAddr).map(_ => ())
  }

  override def initChannelOpen(
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress],
      fundingAmount: CurrencyUnit,
      privateChannel: Boolean): Future[OutputDetails] =
    channelOpener.initPSBTChannelOpen(nodeId,
                                      peerAddrOpt,
                                      fundingAmount,
                                      privateChannel)

  override def completeChannelOpen(
      chanId: ByteVector,
      psbt: PSBT): Future[Unit] =
    channelOpener.completeChannelOpen(NodeId(chanId), psbt).map(_ => ())

  override def cancelChannel(
      chanOutPoint: TransactionOutPoint,
      nodeId: NodeId): Future[Unit] =
    channelOpener.cancelChannel(nodeId).map(_ => ())

  override def start(): Future[Unit] = Future.unit

  override def stop(): Future[Unit] = Future.unit

  override def listTransactions(): Future[Vector[TransactionDetails]] = {
    val txsF = clightning.listTransactions()
    val blockHeightF = clightning.getInfo.map(_.blockheight)

    for {
      blockHeight <- blockHeightF
      txs <- txsF
    } yield {
      txs.map { tx =>
        val confs =
          if (tx.blockheight == 0) 0
          else blockHeight - tx.blockheight + 1

        TransactionDetails(txId = tx.hash,
                           tx = tx.rawtx,
                           numConfirmations = confs,
                           blockHeight = tx.blockheight,
                           label = "")
      }
    }
  }

  override def listChannels(): Future[Vector[ChannelDetails]] = {
    val channelsF = clightning.listChannels()
    val nodeIdF = clightning.nodeId

    for {
      channels <- channelsF
      nodeId <- nodeIdF
    } yield {
      channels.map { channel =>
        val remote =
          if (channel.source == nodeId) channel.destination
          else channel.source

        ChannelDetails(
          alias = "todo", // todo waiting on PR to bitcoin-s
          remotePubkey = remote,
          shortChannelId = channel.short_channel_id,
          public = channel.public,
          amount = channel.satoshis,
          active = channel.active
        )
      }
    }
  }
}
