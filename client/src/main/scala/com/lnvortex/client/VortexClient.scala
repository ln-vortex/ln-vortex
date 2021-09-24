package com.lnvortex.client

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.client.RoundDetails.getNonceOpt
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.client.networking.{P2PClient, Peer}
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.commons.jsonmodels.lnd.UTXOResult
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt16
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.{FutureUtil, StartStopAsync}
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient

import java.net.InetSocketAddress
import scala.concurrent.{Future, Promise}

case class VortexClient(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem,
    val config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {
  import system.dispatcher

  private var handlerP = Promise[ActorRef]()

  private var roundDetails: RoundDetails = NoDetails

  private[client] def setRoundDetails(details: RoundDetails): Unit = {
    roundDetails = details
  }

  private[lnvortex] def getCurrentRoundDetails: RoundDetails =
    roundDetails

  private val knownVersions = Vector(UInt16.zero)

  private val channelOpener = LndChannelOpener(lndRpcClient)

  private[client] def setRound(adv: MixDetails): Unit = {
    if (knownVersions.contains(adv.version)) {
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
    } yield handler ! AskMixDetails(config.network)
  }

  def listCoins(): Future[Vector[UTXOResult]] = {
    lndRpcClient.listUnspent
  }

  def getOutputReferences(outpoints: Vector[TransactionOutPoint]): Future[
    Vector[OutputReference]] = {
    lndRpcClient.listUnspent.map { all =>
      val outRefs =
        all.filter(t => outpoints.contains(t.outPointOpt.get)).map { utxoRes =>
          val output = TransactionOutput(utxoRes.amount, utxoRes.spk)
          OutputReference(utxoRes.outPointOpt.get, output)
        }
      require(outRefs.size == outpoints.size,
              "Did not find all output references")
      outRefs
    }
  }

  def askNonce(): Future[SchnorrNonce] = {
    logger.info("Asking nonce from coordinator")
    roundDetails match {
      case KnownRound(round) =>
        for {
          handler <- handlerP.future
          _ = handler ! AskNonce(round.roundId)
          _ <- AsyncUtil.awaitCondition(() =>
            getNonceOpt(roundDetails).isDefined)
        } yield getNonceOpt(roundDetails).get
      case _: ReceivedNonce | NoDetails | _: InitializedRound =>
        Future.failed(new RuntimeException("In incorrect state"))
    }
  }

  /** Creates a proof of ownership for the input and then locks it
    * @param nonce Round Nonce for the peer
    * @param outputRef OutputReference for the input
    * @return Signed ScriptWitness
    */
  private[client] def createInputProof(
      nonce: SchnorrNonce,
      outputRef: OutputReference): Future[ScriptWitness] = {
    val tx = InputReference.constructInputProofTx(outputRef, nonce)

    for {
      (_, scriptWit) <- lndRpcClient.computeInputScript(tx, 0, outputRef.output)
      _ <- lndRpcClient.leaseOutput(outputRef.outPoint, 3600)
    } yield scriptWit
  }

  private[client] def storeNonce(nonce: SchnorrNonce): Unit = {
    roundDetails match {
      case state @ (NoDetails | _: ReceivedNonce | _: InitializedRound) =>
        throw new IllegalStateException(s"Cannot store nonce at state $state")
      case details: KnownRound =>
        roundDetails = details.nextStage(nonce)
    }
  }

  def registerCoins(
      outpoints: Vector[TransactionOutPoint],
      nodeUri: NodeUri): Future[Unit] = {
    val socketAddress =
      InetSocketAddress.createUnresolved(nodeUri.host, nodeUri.port)
    registerCoins(outpoints, nodeUri.nodeId, Some(socketAddress))
  }

  def registerCoins(
      outpoints: Vector[TransactionOutPoint],
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Future[Unit] = {
    logger.info(
      s"Registering ${outpoints.size} coins to open a channel to $nodeId")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: InitializedRound) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot register coins"))
      case receivedNonce: ReceivedNonce =>
        val round = receivedNonce.round
        for {
          handler <- handlerP.future
          outputRefs <- getOutputReferences(outpoints)

          selectedAmt = outputRefs.map(_.output.value).sum
          inputFees = Satoshis(outputRefs.size) * round.inputFee
          outputFees = Satoshis(2) * round.outputFee
          onChainFees = inputFees + outputFees
          changeAmt = selectedAmt - round.amount - round.mixFee - onChainFees

          _ = require(
            changeAmt > Policy.dustThreshold,
            s"Not enough coins selected, need ${changeAmt - Policy.dustThreshold} more, total selected $selectedAmt")

          inputProofs <- FutureUtil.sequentially(outputRefs)(
            createInputProof(receivedNonce.nonce, _))
          inputRefs = outputRefs.zip(inputProofs).map { case (outRef, proof) =>
            InputReference(outRef, proof)
          }

          changeAddr <- lndRpcClient.getNewAddress
          changeOutput = TransactionOutput(changeAmt, changeAddr.scriptPubKey)

          channelDetails <- channelOpener.initPSBTChannelOpen(
            nodeId = nodeId,
            peerAddrOpt = peerAddrOpt,
            fundingAmount = receivedNonce.round.amount,
            privateChannel = false)
          mixOutput = channelDetails.output

          hashedOutput = BobMessage.calculateChallenge(mixOutput, round.roundId)

          tweaks = freshBlindingTweaks(signerPubKey = round.publicKey,
                                       signerNonce = receivedNonce.nonce)
          challenge = BlindSchnorrUtil.generateChallenge(
            signerPubKey = round.publicKey,
            signerNonce = receivedNonce.nonce,
            blindingTweaks = tweaks,
            message = hashedOutput)
        } yield {
          val details = InitDetails(inputs = outputRefs,
                                    changeOutput = changeOutput,
                                    chanId = channelDetails.chanId,
                                    mixOutput = mixOutput,
                                    tweaks = tweaks)
          roundDetails = receivedNonce.nextStage(details)

          handler ! RegisterInputs(inputRefs, challenge, changeOutput)
        }
    }
  }

  def processBlindOutputSig(blindOutputSig: FieldElement): Future[Unit] = {
    logger.info("Got blind sig from coordinator, processing..")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: MixOutputRegistered | _: PSBTSigned) =>
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

  private[client] def signPSBT(
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

  def validateAndSignPsbt(unsigned: PSBT): Future[PSBT] = {
    logger.info("Received unsigned psbt from coordinator, verifying...")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsRegistered | _: PSBTSigned) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot validateAndSignPsbt"))
      case state: MixOutputRegistered =>
        roundDetails = state.nextStage
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
            _ <- channelOpener.fundPendingChannel(state.initDetails.chanId,
                                                  unsigned)
            _ = logger.info("Valid with channel peer, signing")
            // sign to be sent to coordinator
            signed <- signPSBT(unsigned, inputs)
          } yield signed
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
          _: InputsRegistered | _: MixOutputRegistered) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot completeRound"))
      case _: PSBTSigned =>
        logger.info("Coinjoin complete!!")
        for {
          _ <- lndRpcClient.publishTransaction(signedTx)
          _ <- stop()
          _ <- getNewRound
        } yield ()
    }
  }
}
