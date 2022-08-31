package com.lnvortex.lnd

import akka.actor.ActorSystem
import com.lnvortex.core.api._
import com.lnvortex.core.{InputReference, UnspentCoin}
import com.lnvortex.lnd.LndVortexWallet.getSignMethod
import lnrpc.{AddressType, NewAddressRequest, NodeInfoRequest}
import org.bitcoins.core.config.{BitcoinNetwork, BitcoinNetworks}
import org.bitcoins.core.currency._
import org.bitcoins.core.number.{UInt32, UInt64}
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.LndUtils._
import scodec.bits.ByteVector
import signrpc.{SignDescriptor, SignMethod, SignReq}
import walletrpc.LabelTransactionRequest

import java.net.InetSocketAddress
import scala.concurrent.duration._
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

  override def getBlockHeight(): Future[Int] = {
    lndRpcClient.getInfo.map(_.blockHeight.toInt)
  }

  override def getNewAddress(scriptType: ScriptType): Future[BitcoinAddress] = {
    val req: NewAddressRequest = NewAddressRequest(
      LndVortexWallet.addressTypeFromScriptType(scriptType))

    lndRpcClient.lnd
      .newAddress(req)
      .map(r => BitcoinAddress.fromString(r.address))
  }

  override def getChangeAddress(
      scriptType: ScriptType): Future[BitcoinAddress] =
    getNewAddress(scriptType)

  override def listCoins(): Future[Vector[UnspentCoin]] = {
    lndRpcClient.listUnspent.map(_.map { utxo =>
      UnspentCoin(utxo.address,
                  utxo.amount,
                  utxo.outPointOpt.get,
                  utxo.confirmations > 0,
                  anonSet = 1,
                  warning = None,
                  isChange = false)
    })
  }

  override def createInputProof(
      nonce: SchnorrNonce,
      outputRef: OutputReference,
      reserveDuration: FiniteDuration): Future[ScriptWitness] = {
    val tx = InputReference.constructInputProofTx(outputRef, nonce)

    val (signMethod, hashType) = getSignMethod(outputRef.output)

    val signDescriptor = SignDescriptor(output = Some(outputRef.output),
                                        sighash = UInt32(hashType.num),
                                        signMethod = signMethod,
                                        inputIndex = 0)

    // Add input's prev out and a fake prev out for the fake input
    val prevOuts = Vector(outputRef.output, EmptyTransactionOutput)
    val signReq = SignReq(tx.bytes, Vector(signDescriptor), prevOuts)

    for {
      (_, scriptWit) <- lndRpcClient.computeInputScript(signReq).map(_.head)
      _ <- lndRpcClient.leaseOutput(outputRef.outPoint,
                                    reserveDuration.toSeconds)
    } yield scriptWit
  }

  override def signPSBT(
      unsigned: PSBT,
      inputs: Vector[OutputReference]): Future[PSBT] = {
    val txOutpoints = unsigned.transaction.inputs.map(_.previousOutput)
    val prevOuts =
      unsigned.inputMaps
        .map(_.witnessUTXOOpt.map(_.witnessUTXO))
        .map(_.getOrElse(
          throw new RuntimeException("Missing witness UTXO in psbt")))

    require(prevOuts.size == unsigned.transaction.inputs.size,
            "Number of previous outputs does not match number of inputs")

    val signDescriptors = inputs.map { input =>
      val idx = txOutpoints.indexOf(input.outPoint)
      val (signMethod, hashType) = getSignMethod(input.output)

      SignDescriptor(output = Some(input.output),
                     sighash = UInt32(hashType.num),
                     signMethod = signMethod,
                     inputIndex = idx)
    }

    val signReq = SignReq(unsigned.transaction.bytes, signDescriptors, prevOuts)

    lndRpcClient.computeInputScript(signReq).map { sigs =>
      val indexes = signDescriptors.map(_.inputIndex)
      sigs.zip(indexes).foldLeft(unsigned) {
        case (psbt, ((scriptSig, witness), idx)) =>
          psbt.addFinalizedScriptWitnessToInput(scriptSig, witness, idx)
      }
    }
  }

  override def broadcastTransaction(transaction: Transaction): Future[Unit] =
    lndRpcClient.publishTransaction(transaction).map(_ => ())

  override def labelTransaction(
      txId: DoubleSha256Digest,
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

  def cancelPendingChannel(chanId: ByteVector): Future[Unit] = {
    channelOpener.cancelPendingChannel(chanId)
  }

  override def start(): Future[Unit] = {
    for {
      version <- lndRpcClient.getVersion()
    } yield {
      if (version.version < LndVortexWallet.MIN_LND_VERSION) {
        throw new RuntimeException(
          s"LND version ${version.version} is too old. " +
            s"Minimum version is ${LndVortexWallet.MIN_LND_VERSION}")
      }
      if (!version.buildTags.contains("signrpc")) {
        throw new RuntimeException(
          s"LND must have signrpc build tag to use Vortex")
      }
    }
  }

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
    val allChannelsF = for {
      channels <- lndRpcClient.listChannels()
      pending <- lndRpcClient.listPendingChannels()
    } yield (channels, pending)

    allChannelsF
      .flatMap { case (channels, pending) =>
        val channelFs = channels.map { channel =>
          val request = NodeInfoRequest(channel.remotePubkey)
          val aliasF = lndRpcClient.lnd
            .getNodeInfo(request)
            .map(_.getNode.alias)
            .recover(_ => "")

          val outPoint = TransactionOutPoint.fromString(channel.channelPoint)

          aliasF.map { alias =>
            ChannelDetails(
              alias = alias,
              outPoint = outPoint,
              remotePubkey = NodeId(channel.remotePubkey),
              shortChannelId = ShortChannelId(channel.chanId),
              public = !channel.`private`,
              amount = Satoshis(channel.capacity),
              active = channel.active,
              anonSet = 1
            )
          }
        }

        val pendingFs =
          pending.pendingOpenChannels.flatMap(_.channel).map { channel =>
            val request = NodeInfoRequest(channel.remoteNodePub)
            val aliasF = lndRpcClient.lnd
              .getNodeInfo(request)
              .map(_.getNode.alias)
              .recover(_ => "")

            val outPoint = TransactionOutPoint.fromString(channel.channelPoint)

            aliasF.map { alias =>
              ChannelDetails(
                alias = alias,
                outPoint = outPoint,
                remotePubkey = NodeId(channel.remoteNodePub),
                shortChannelId =
                  ShortChannelId(UInt64.zero), // fixme make optional?
                public = !channel.`private`,
                amount = Satoshis(channel.capacity),
                active = false,
                anonSet = 1
              )
            }
          }

        for {
          chans <- Future.sequence(channelFs)
          pending <- Future.sequence(pendingFs)
        } yield chans ++ pending
      }
  }
}

object LndVortexWallet {

  final val MIN_LND_VERSION = "0.15.1"

  def addressTypeFromScriptType(scriptType: ScriptType): AddressType = {
    scriptType match {
      case tpe @ (ScriptType.PUBKEY | ScriptType.PUBKEYHASH |
          ScriptType.NONSTANDARD | ScriptType.MULTISIG | ScriptType.CLTV |
          ScriptType.CSV | ScriptType.NONSTANDARD_IF_CONDITIONAL |
          ScriptType.NOT_IF_CONDITIONAL | ScriptType.MULTISIG_WITH_TIMEOUT |
          ScriptType.PUBKEY_WITH_TIMEOUT | ScriptType.NULLDATA |
          ScriptType.WITNESS_UNKNOWN | ScriptType.WITNESS_COMMITMENT) =>
        throw new IllegalArgumentException(s"Unknown address type $tpe")
      case ScriptType.SCRIPTHASH => AddressType.NESTED_PUBKEY_HASH
      case ScriptType.WITNESS_V0_KEYHASH =>
        AddressType.WITNESS_PUBKEY_HASH
      case ScriptType.WITNESS_V0_SCRIPTHASH =>
        throw new IllegalArgumentException(
          s"Unknown address type ${ScriptType.WITNESS_V0_SCRIPTHASH}")
      case ScriptType.WITNESS_V1_TAPROOT =>
        AddressType.TAPROOT_PUBKEY
    }
  }

  def getSignMethod(output: TransactionOutput): (SignMethod, HashType) = {
    output.scriptPubKey.scriptType match {
      case ScriptType.PUBKEY | ScriptType.PUBKEYHASH | ScriptType.NONSTANDARD |
          ScriptType.MULTISIG | ScriptType.CLTV | ScriptType.CSV |
          ScriptType.NONSTANDARD_IF_CONDITIONAL |
          ScriptType.NOT_IF_CONDITIONAL | ScriptType.MULTISIG_WITH_TIMEOUT |
          ScriptType.PUBKEY_WITH_TIMEOUT | ScriptType.NULLDATA |
          ScriptType.WITNESS_UNKNOWN | ScriptType.WITNESS_COMMITMENT =>
        (SignMethod.SIGN_METHOD_WITNESS_V0, HashType.sigHashAll)
      case ScriptType.SCRIPTHASH =>
        (SignMethod.SIGN_METHOD_WITNESS_V0, HashType.sigHashAll)
      case ScriptType.WITNESS_V0_KEYHASH =>
        (SignMethod.SIGN_METHOD_WITNESS_V0, HashType.sigHashAll)
      case ScriptType.WITNESS_V0_SCRIPTHASH =>
        (SignMethod.SIGN_METHOD_WITNESS_V0, HashType.sigHashAll)
      case ScriptType.WITNESS_V1_TAPROOT =>
        (SignMethod.SIGN_METHOD_TAPROOT_KEY_SPEND_BIP0086,
         HashType.sigHashDefault)
    }
  }
}
