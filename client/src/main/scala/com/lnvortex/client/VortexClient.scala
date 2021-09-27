package com.lnvortex.client

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.client.RoundDetails.getNonceOpt
import com.lnvortex.client.api.CoinJoinWalletApi
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.client.networking.{P2PClient, Peer}
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt16
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.{FutureUtil, StartStopAsync}
import org.bitcoins.crypto._

import java.net.InetSocketAddress
import scala.concurrent.{Future, Promise}

case class VortexClient[+T <: CoinJoinWalletApi](coinjoinWallet: T)(implicit
    system: ActorSystem,
    val config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {
  import system.dispatcher

  private var handlerP = Promise[ActorRef]()

  private var roundDetails: RoundDetails = NoDetails

  private[client] def setRoundDetails(details: RoundDetails): Unit =
    roundDetails = details

  private[lnvortex] def getCurrentRoundDetails: RoundDetails =
    roundDetails

  private[client] def setRound(adv: MixDetails): Unit = {
    if (VortexClient.knownVersions.contains(adv.version)) {
      roundDetails = KnownRound(adv)
    } else {
      throw new RuntimeException(
        s"Received unknown mix version ${adv.version.toInt}, consider updating software")
    }
  }

  lazy val peer: Peer = Peer(socket = config.coordinatorAddress,
                             socks5ProxyParams = config.socks5ProxyParams)

  override def start(): Future[Unit] = getNewRound

  override def stop(): Future[Unit] = {
    handlerP.future.map { handler =>
      system.stop(handler)
    }
  }

  private def getNewRound: Future[Unit] = {
    logger.info("Getting new round from coordinator")

    roundDetails = NoDetails
    handlerP = Promise[ActorRef]()
    for {
      _ <- P2PClient.connect(peer, this, Some(handlerP))
      handler <- handlerP.future
    } yield handler ! AskMixDetails(coinjoinWallet.network)
  }

  def listCoins: Future[Vector[UnspentCoin]] = coinjoinWallet.listCoins

  def askNonce(): Future[SchnorrNonce] = {
    logger.info("Asking nonce from coordinator")
    roundDetails match {
      case KnownRound(round) =>
        for {
          handler <- handlerP.future
          _ = handler ! AskNonce(round.roundId)
          _ <- AsyncUtil.awaitCondition(() =>
            getNonceOpt(roundDetails).isDefined)
        } yield {
          val nonce = getNonceOpt(roundDetails).get
          logger.info(s"Got nonce from coordinator $nonce")
          nonce
        }
      case NoDetails | _: ReceivedNonce | _: InputsScheduled |
          _: InitializedRound =>
        Future.failed(new RuntimeException("In incorrect state"))
    }
  }

  private[client] def storeNonce(nonce: SchnorrNonce): Unit = {
    roundDetails match {
      case state @ (NoDetails | _: ReceivedNonce | _: InitializedRound |
          _: InputsScheduled) =>
        throw new IllegalStateException(s"Cannot store nonce at state $state")
      case details: KnownRound =>
        roundDetails = details.nextStage(nonce)
    }
  }

  def queueCoins(
      outputRefs: Vector[OutputReference],
      nodeUri: NodeUri): Unit = {
    queueCoins(outputRefs, nodeUri.nodeId, Some(nodeUri.socketAddress))
  }

  def queueCoins(
      outputRefs: Vector[OutputReference],
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Unit = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: InputsScheduled |
          _: InputsRegistered | _: MixOutputRegistered | _: PSBTSigned) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot sendRegisteredCoins")
      case receivedNonce: ReceivedNonce =>
        logger.info(
          s"Queueing ${outputRefs.size} coins to open a channel to $nodeId")
        roundDetails = receivedNonce.nextStage(outputRefs, nodeId, peerAddrOpt)
    }
  }

  private[client] def registerCoins(
      roundId: DoubleSha256Digest): Future[Unit] = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InitializedRound) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot register coins"))
      case scheduled: InputsScheduled =>
        val round = scheduled.round
        require(scheduled.round.roundId == roundId,
                "Attempted to get coins for a different round")

        val outputRefs = scheduled.inputs
        val selectedAmt = outputRefs.map(_.output.value).sum
        val inputFees = Satoshis(outputRefs.size) * round.inputFee
        val outputFees = Satoshis(2) * round.outputFee
        val onChainFees = inputFees + outputFees
        val changeAmt = selectedAmt - round.amount - round.mixFee - onChainFees

        require(
          changeAmt > Policy.dustThreshold,
          s"Not enough coins selected, need ${changeAmt - Policy.dustThreshold} more, total selected $selectedAmt")

        for {
          handler <- handlerP.future
          inputProofs <- FutureUtil.sequentially(outputRefs)(
            coinjoinWallet.createInputProof(scheduled.nonce, _))

          inputRefs = outputRefs.zip(inputProofs).map { case (outRef, proof) =>
            InputReference(outRef, proof)
          }

          changeAddr <- coinjoinWallet.getChangeAddress
          changeOutput = TransactionOutput(changeAmt, changeAddr.scriptPubKey)

          channelDetails <- coinjoinWallet.initChannelOpen(
            nodeId = scheduled.nodeId,
            peerAddrOpt = scheduled.peerAddrOpt,
            fundingAmount = scheduled.round.amount,
            privateChannel = false)
        } yield {
          val mixOutput = channelDetails.output
          val hashedOutput =
            BobMessage.calculateChallenge(mixOutput, round.roundId)

          val tweaks = freshBlindingTweaks(signerPubKey = round.publicKey,
                                           signerNonce = scheduled.nonce)
          val challenge = BlindSchnorrUtil.generateChallenge(
            signerPubKey = round.publicKey,
            signerNonce = scheduled.nonce,
            blindingTweaks = tweaks,
            message = hashedOutput)

          val details = InitDetails(inputs = outputRefs,
                                    changeOutput = changeOutput,
                                    chanId = channelDetails.id,
                                    mixOutput = mixOutput,
                                    tweaks = tweaks)

          roundDetails = scheduled.nextStage(details)

          handler ! RegisterInputs(inputRefs, challenge, changeOutput)
        }
    }
  }

  def processBlindOutputSig(blindOutputSig: FieldElement): Future[Unit] = {
    logger.info("Got blind sig from coordinator, processing..")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: MixOutputRegistered | _: PSBTSigned) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot processBlindOutputSig"))
      case details: InputsRegistered =>
        val mixOutput = details.initDetails.mixOutput
        val publicKey = details.round.publicKey
        val nonce = details.nonce
        val tweaks = details.initDetails.tweaks

        val challenge =
          BobMessage.calculateChallenge(mixOutput, details.round.roundId)
        val sig = BlindSchnorrUtil.unblindSignature(blindSig = blindOutputSig,
                                                    signerPubKey = publicKey,
                                                    signerNonce = nonce,
                                                    blindingTweaks = tweaks,
                                                    message = challenge)

        val bobHandlerP = Promise[ActorRef]()

        logger.info("Send channel output as Bob")
        for {
          _ <- P2PClient.connect(peer, this, Some(bobHandlerP))
          bobHandler <- bobHandlerP.future
        } yield {
          roundDetails = details.nextStage
          bobHandler ! BobMessage(sig, mixOutput)
        }
    }
  }

  def validateAndSignPsbt(unsigned: PSBT): Future[PSBT] = {
    logger.info("Received unsigned psbt from coordinator, verifying...")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: InputsRegistered | _: PSBTSigned) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot validateAndSignPsbt"))
      case state: MixOutputRegistered =>
        val inputs: Vector[OutputReference] = state.initDetails.inputs
        lazy val myOutpoints = inputs.map(_.outPoint)
        lazy val txOutpoints = unsigned.transaction.inputs.map(_.previousOutput)
        // should we care if they don't have our inputs?
        lazy val hasInputs = myOutpoints.forall(txOutpoints.contains)

        lazy val hasChangeOutput =
          unsigned.transaction.outputs.contains(state.initDetails.changeOutput)
        lazy val hasMixOutput =
          unsigned.transaction.outputs.contains(state.initDetails.mixOutput)

        if (hasMixOutput && hasChangeOutput && hasInputs) {
          logger.info("PSBT is valid, giving channel peer")
          for {
            // tell peer about funding psbt
            _ <- coinjoinWallet.completeChannelOpen(state.initDetails.chanId,
                                                    unsigned)
            _ = logger.info("Valid with channel peer, signing")
            // sign to be sent to coordinator
            signed <- coinjoinWallet.signPSBT(unsigned, inputs)
          } yield {
            roundDetails = state.nextStage(signed)
            signed
          }
        } else {
          Future.failed(
            new RuntimeException(
              "Received PSBT did not contain our inputs or outputs"))
        }
    }
  }

  def completeRound(signedTx: Transaction): Future[Unit] = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: InputsRegistered | _: MixOutputRegistered) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot completeRound"))
      case _: PSBTSigned =>
        logger.info("Coinjoin complete!!")
        for {
          _ <- coinjoinWallet.broadcastTransaction(signedTx)
          _ <- stop()
          _ <- getNewRound
        } yield ()
    }
  }
}

object VortexClient {
  val knownVersions: Vector[UInt16] = Vector(UInt16.zero)
}
