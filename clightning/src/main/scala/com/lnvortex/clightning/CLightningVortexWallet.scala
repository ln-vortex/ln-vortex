package com.lnvortex.clightning

import akka.actor.ActorSystem
import com.bitcoins.clightning.rpc.CLightningRpcClient
import com.lnvortex.core.api.{OutputDetails, VortexWalletApi}
import com.lnvortex.core.{InputReference, UnspentCoin}
import org.bitcoins.core.config._
import org.bitcoins.core.currency._
import org.bitcoins.core.hd.AddressType
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
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

  override def getNewAddress: Future[BitcoinAddress] =
    clightning.getNewAddress(AddressType.SegWit)

  override def getChangeAddress: Future[BitcoinAddress] =
    clightning.getNewAddress(AddressType.SegWit)

  override def listCoins: Future[Vector[UnspentCoin]] = {
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
      txId: DoubleSha256DigestBE,
      label: String): Future[Unit] = Future.unit

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
}
