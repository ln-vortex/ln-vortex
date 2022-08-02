package com.lnvortex.client

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core.RoundDetails.{getMixDetailsOpt, getNonceOpt}
import com.lnvortex.client.VortexClientException._
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.client.db.UTXODAO
import com.lnvortex.client.networking.{P2PClient, Peer}
import com.lnvortex.core._
import com.lnvortex.core.api.{OutputDetails, VortexWalletApi}
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.number.UInt16
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.{BitcoinAddress, BlockStamp}
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util.{FutureUtil, StartStopAsync, TimeUtil}
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._

import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

case class VortexClient[+T <: VortexWalletApi](vortexWallet: T)(implicit
    system: ActorSystem,
    val config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {
  import system.dispatcher

  lazy val utxoDAO: UTXODAO = UTXODAO()

  private[client] var handlerP = Promise[ActorRef]()

  private var roundDetails: RoundDetails = NoDetails

  private[client] def setRoundDetails(details: RoundDetails): Unit =
    roundDetails = details

  def getCurrentRoundDetails: RoundDetails = roundDetails

  private[lnvortex] def setRound(adv: MixDetails): Unit = {
    if (VortexClient.knownVersions.contains(adv.version)) {
      roundDetails match {
        case NoDetails | _: ReceivedNonce | _: KnownRound |
            _: InputsScheduled =>
          roundDetails = KnownRound(adv)
        case state: InitializedRound =>
          throw new IllegalStateException(s"Cannot set round at state $state")
      }
    } else {
      throw new RuntimeException(
        s"Received unknown mix version ${adv.version.toInt}, consider updating software")
    }
  }

  lazy val peer: Peer = Peer(socket = config.coordinatorAddress,
                             socks5ProxyParams = config.socks5ProxyParams)

  override def start(): Future[Unit] = {
    for {
      coins <- vortexWallet.listCoins()
      _ <- utxoDAO.createMissing(coins.map(_.outPoint))
      _ <- getNewRound
    } yield ()
  }

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
    } yield handler ! AskMixDetails(vortexWallet.network)
  }

  def listCoins(): Future[Vector[UnspentCoin]] = {
    for {
      coins <- vortexWallet.listCoins()
      utxoDbs <- utxoDAO.findByOutPoints(coins.map(_.outPoint))
    } yield {
      coins.map { coin =>
        val utxoOpt = utxoDbs.find(_.outPoint == coin.outPoint)
        utxoOpt match {
          case Some(utxo) =>
            coin.copy(anonSet = utxo.anonSet, isChange = utxo.isChange)
          case None => coin
        }
      }
    }
  }

  def askNonce(): Future[SchnorrNonce] = {
    logger.info("Asking nonce from coordinator")
    roundDetails match {
      case KnownRound(round) =>
        for {
          handler <- handlerP.future
          _ = handler ! AskNonce(round.roundId)
          _ <- AsyncUtil
            .awaitCondition(() => getNonceOpt(roundDetails).isDefined,
                            interval = 100.milliseconds,
                            maxTries = 300)
            .recover { case _: Throwable =>
              throw new RuntimeException(
                "Did not receive response from coordinator")
            }
        } yield {
          val nonce = getNonceOpt(roundDetails).get
          logger.info(s"Got nonce from coordinator $nonce")
          nonce
        }
      case state @ (NoDetails | _: ReceivedNonce | _: InputsScheduled |
          _: InitializedRound) =>
        Future.failed(
          new IllegalStateException(s"Cannot ask nonce at state $state"))
    }
  }

  private[lnvortex] def storeNonce(nonce: SchnorrNonce): Unit = {
    roundDetails match {
      case state @ (NoDetails | _: ReceivedNonce | _: InitializedRound |
          _: InputsScheduled) =>
        throw new IllegalStateException(s"Cannot store nonce at state $state")
      case details: KnownRound =>
        roundDetails = details.nextStage(nonce)
    }
  }

  def cancelRegistration(): Future[Unit] = {
    logger.info("Canceling registration")
    getNonceOpt(roundDetails) match {
      case Some(nonce) =>
        handlerP.future.map { handler =>
          // .get is safe, can't have nonce without mix details
          val mixDetails = getMixDetailsOpt(roundDetails).get
          handler ! CancelRegistrationMessage(nonce, mixDetails.roundId)

          roundDetails = KnownRound(mixDetails)
        }
      case None =>
        Future.failed(new IllegalStateException("No registration to cancel"))
    }
  }

  private def checkMinChanSize(
      amount: CurrencyUnit,
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Future[Unit] = {
    val connectF = vortexWallet.isConnected(nodeId).flatMap { connected =>
      if (!connected) {
        peerAddrOpt match {
          case None =>
            Future.failed(new IllegalArgumentException(
              s"Not connected to peer $nodeId, you must connect with them in order to open a channel to them"))
          case Some(peerAddr) =>
            val peerStr = s"${peerAddr.getHostName}:${peerAddr.getPort}"
            logger.info(s"Connecting to peer $peerStr")
            vortexWallet
              .connect(nodeId, peerAddr)
              .map(_ => logger.info("Connected to peer"))
              .recover { case ex: Throwable =>
                logger.error(s"Failed to connect to peer $nodeId@$peerStr", ex)
                throw new RuntimeException(
                  s"Failed to connect to peer $nodeId@$peerStr")
              }
        }
      } else Future.unit
    }

    connectF.flatMap { _ =>
      val f = for {
        details <- vortexWallet.initChannelOpen(nodeId,
                                                peerAddrOpt,
                                                amount,
                                                privateChannel = true)
        _ <- vortexWallet.cancelPendingChannel(details.id)
      } yield logger.debug("Peer has a correct min chan size")

      f.recover { case ex: Throwable =>
        logger.error(s"Error from ln node: ${ex.getMessage}", ex)
        throw new RuntimeException(s"$amount is under peer's min chan size")
      }
    }
  }

  def getCoinsAndQueue(
      outPoints: Vector[TransactionOutPoint],
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Future[Unit] = {
    listCoins().flatMap { utxos =>
      val coins = utxos
        .filter(u => outPoints.contains(u.outPoint))
        .map(_.outputReference)
      queueCoinsAndTryConnect(coins, nodeId, peerAddrOpt)
    }
  }

  def getCoinsAndQueue(
      outPoints: Vector[TransactionOutPoint],
      address: BitcoinAddress): Future[Unit] = {
    listCoins().map { utxos =>
      val coins = utxos
        .filter(u => outPoints.contains(u.outPoint))
        .map(_.outputReference)
      queueCoins(coins, address)
    }
  }

  def queueCoins(
      outputRefs: Vector[OutputReference],
      nodeUri: NodeUri): Future[Unit] = {
    queueCoinsAndTryConnect(outputRefs,
                            nodeUri.nodeId,
                            Some(nodeUri.socketAddress))
  }

  def queueCoins(
      outputRefs: Vector[OutputReference],
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Future[Unit] = {
    queueCoinsAndTryConnect(outputRefs, nodeId, peerAddrOpt)
  }

  private def queueCoinsAndTryConnect(
      outputRefs: Vector[OutputReference],
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Future[Unit] = {
    require(outputRefs.nonEmpty, "Must include inputs")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: InputsScheduled |
          _: InputsRegistered | _: MixOutputRegistered | _: PSBTSigned) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot queue coins")
      case receivedNonce: ReceivedNonce =>
        val round = receivedNonce.round

        val isRemix =
          outputRefs.size == 1 && outputRefs.head.output.value == round.amount
        val target = round.getTargetAmount(isRemix)

        logger.info(
          s"Queueing ${outputRefs.size} coins to open a channel to $nodeId")
        // todo check if peer supports taproot channel if needed
        if (
          !outputRefs.forall(
            _.output.scriptPubKey.scriptType == round.inputType)
        ) {
          throw new InvalidInputException(
            s"Inputs must be of type ${round.inputType}")
        } else if (outputRefs.map(_.output.value).sum < round.amount) {
          throw new InvalidInputException(
            s"Must select more inputs to find round, needed ${round.amount}")
        } else if (round.outputType != ScriptType.WITNESS_V0_SCRIPTHASH) {
          throw new InvalidMixedOutputException(
            "This version of lnd only supports SegwitV0 channels")
        } else if (
          outputRefs.distinctBy(_.output.scriptPubKey).size != outputRefs.size
        ) {
          throw new InvalidInputException(
            s"Cannot have inputs from duplicate addresses")
        } else if (outputRefs.map(_.output.value).sum < target) {
          throw new InvalidInputException(
            s"Must select more inputs to find round, needed $target")
        } else if (!VortexUtils.isMinimalSelection(outputRefs, target)) {
          throw new InvalidInputException(
            s"Must select minimal inputs for target amount, please deselect some")
        } else {
          checkMinChanSize(amount = round.amount,
                           nodeId = nodeId,
                           peerAddrOpt = peerAddrOpt).map { _ =>
            roundDetails = receivedNonce.nextStage(inputs = outputRefs,
                                                   addressOpt = None,
                                                   nodeIdOpt = Some(nodeId),
                                                   peerAddrOpt = peerAddrOpt)
            logger.info("Coins queued!")
          }
        }
    }
  }

  def queueCoins(
      outputRefs: Vector[OutputReference],
      address: BitcoinAddress): Unit = {
    require(outputRefs.nonEmpty, "Must include inputs")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: InputsScheduled |
          _: InputsRegistered | _: MixOutputRegistered | _: PSBTSigned) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot queue coins")
      case receivedNonce: ReceivedNonce =>
        val round = receivedNonce.round

        val isRemix =
          outputRefs.size == 1 && outputRefs.head.output.value == round.amount
        val target = round.getTargetAmount(isRemix)

        logger.info(
          s"Queueing ${outputRefs.size} coins for collaborative transaction")
        if (
          !outputRefs.forall(
            _.output.scriptPubKey.scriptType == round.inputType)
        ) {
          throw new InvalidInputException(
            s"Inputs must be of type ${round.inputType}")
        } else if (address.scriptPubKey.scriptType != round.outputType) {
          throw new InvalidMixedOutputException(
            s"Address must be of type ${round.outputType}")
        } else if (
          outputRefs.distinctBy(_.output.scriptPubKey).size != outputRefs.size
        ) {
          throw new InvalidInputException(
            s"Cannot have inputs from duplicate addresses")
        } else if (outputRefs.map(_.output.value).sum < target) {
          throw new InvalidInputException(
            s"Must select more inputs to find round, needed $target")
        } else if (!VortexUtils.isMinimalSelection(outputRefs, target)) {
          throw new InvalidInputException(
            s"Must select minimal inputs for target amount, please deselect some")
        } else {
          roundDetails = receivedNonce.nextStage(inputs = outputRefs,
                                                 addressOpt = Some(address),
                                                 nodeIdOpt = None,
                                                 peerAddrOpt = None)
          logger.info("Coins queued!")
        }
    }
  }

  private[lnvortex] def registerCoins(
      roundId: DoubleSha256Digest,
      inputFee: CurrencyUnit,
      outputFee: CurrencyUnit,
      changeOutputFee: CurrencyUnit): Future[RegisterInputs] = {
    roundDetails match {
      case state @ (NoDetails | _: ReceivedNonce | _: InitializedRound) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot register coins"))
      case _: KnownRound =>
        // Sometimes we get the AskInputs before the round details + nonce,
        // so we need to wait for the round details to be set.
        AsyncUtil
          .awaitCondition(
            () => roundDetails.status == ClientStatus.InputsScheduled,
            interval = 100.milliseconds,
            maxTries = 300)
          .recover { _ =>
            throw new IllegalStateException(
              s"At invalid state ${roundDetails.status}, cannot register coins")
          }
          .flatMap { _ =>
            registerCoins(roundId, inputFee, outputFee, changeOutputFee)
          }
      case scheduled: InputsScheduled =>
        val round = scheduled.round
        require(scheduled.round.roundId == roundId,
                "Attempted to get coins for a different round")

        val outputRefs = scheduled.inputs
        val selectedAmt = outputRefs.map(_.output.value).sum
        val inputFees = Satoshis(outputRefs.size) * inputFee
        val onChainFees = inputFees + outputFee + changeOutputFee
        val changeAmt = selectedAmt - round.amount - round.mixFee - onChainFees

        val needsChange = changeAmt >= Policy.dustThreshold

        // Reserve utxos until 10 minutes after the round is scheduled.
        val timeUtilRound =
          round.time.toLong + 600 - TimeUtil.currentEpochSecond

        for {
          inputProofs <- FutureUtil.sequentially(outputRefs)(
            vortexWallet
              .createInputProof(scheduled.nonce, _, timeUtilRound.seconds))

          inputRefs = outputRefs.zip(inputProofs).map { case (outRef, proof) =>
            InputReference(outRef, proof)
          }

          changeAddrOpt <-
            if (needsChange)
              vortexWallet.getChangeAddress(round.changeType).map(Some(_))
            else FutureUtil.none

          channelDetails <- scheduled.nodeIdOpt match {
            case Some(nodeId) =>
              vortexWallet
                .initChannelOpen(nodeId = nodeId,
                                 peerAddrOpt = scheduled.peerAddrOpt,
                                 fundingAmount = scheduled.round.amount,
                                 privateChannel = false)
                .map { details =>
                  if (
                    details.address.scriptPubKey.scriptType != round.outputType
                  ) {
                    throw new InvalidMixedOutputException(
                      s"Channel type is invalid, need output of type ${round.outputType}")
                  } else details
                }
            case None =>
              val addressF = scheduled.addressOpt match {
                case Some(addr) =>
                  if (addr.scriptPubKey.scriptType == round.outputType) {
                    Future.successful(addr)
                  } else {
                    Future.failed(new InvalidMixedOutputException(
                      s"Need address of type ${round.outputType}, got ${addr.scriptPubKey.scriptType}"))
                  }
                case None => vortexWallet.getNewAddress(round.outputType)
              }

              addressF.map { addr =>
                val id = CryptoUtil.sha256(addr.value).bytes
                OutputDetails(id, scheduled.round.amount, addr)
              }
          }
        } yield {
          val mixOutput = channelDetails.output
          val hashedOutput =
            RegisterMixOutput.calculateChallenge(mixOutput, round.roundId)

          val tweaks = freshBlindingTweaks(signerPubKey = round.publicKey,
                                           signerNonce = scheduled.nonce)
          val challenge = BlindSchnorrUtil.generateChallenge(
            signerPubKey = round.publicKey,
            signerNonce = scheduled.nonce,
            blindingTweaks = tweaks,
            message = hashedOutput)

          val details = InitDetails(
            inputs = outputRefs,
            nodeIdOpt = scheduled.nodeIdOpt,
            addressOpt = scheduled.addressOpt,
            peerAddrOpt = scheduled.peerAddrOpt,
            changeSpkOpt = changeAddrOpt.map(_.scriptPubKey),
            chanId = channelDetails.id,
            mixOutput = mixOutput,
            tweaks = tweaks
          )

          roundDetails =
            scheduled.nextStage(details, inputFee, outputFee, changeOutputFee)

          RegisterInputs(inputRefs,
                         challenge,
                         changeAddrOpt.map(_.scriptPubKey))
        }
    }
  }

  def processBlindOutputSig(blindOutputSig: FieldElement): RegisterMixOutput = {
    logger.info("Got blind sig from coordinator, processing..")
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: MixOutputRegistered | _: PSBTSigned) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot processBlindOutputSig")
      case details: InputsRegistered =>
        val mixOutput = details.initDetails.mixOutput
        val publicKey = details.round.publicKey
        val nonce = details.nonce
        val tweaks = details.initDetails.tweaks

        val challenge =
          RegisterMixOutput.calculateChallenge(mixOutput, details.round.roundId)
        val sig = BlindSchnorrUtil.unblindSignature(blindSig = blindOutputSig,
                                                    signerPubKey = publicKey,
                                                    signerNonce = nonce,
                                                    blindingTweaks = tweaks,
                                                    message = challenge)
        roundDetails = details.nextStage

        RegisterMixOutput(sig, mixOutput)
    }
  }

  private[client] def sendOutputMessageAsBob(
      msg: RegisterMixOutput): Future[Unit] = {
    val bobHandlerP = Promise[ActorRef]()

    logger.info("Sending channel output as Bob")
    for {
      _ <- P2PClient.connect(peer, this, Some(bobHandlerP))
      bobHandler <- bobHandlerP.future
    } yield {
      bobHandler ! msg
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
        vortexWallet.getBlockHeight().flatMap { height =>
          val inputs: Vector[OutputReference] = state.initDetails.inputs
          lazy val myOutpoints = inputs.map(_.outPoint)
          val tx = unsigned.transaction
          lazy val txOutpoints = tx.inputs.map(_.previousOutput)
          // should we care if they don't have our inputs?
          lazy val missingInputs = myOutpoints.filterNot(txOutpoints.contains)

          // make sure this can be broadcast now
          val goodLockTime = BlockStamp(tx.lockTime) match {
            case BlockStamp.BlockHeight(lockHeight) =>
              lockHeight <= height + 1
            case BlockStamp.BlockTime(time) =>
              time.toLong <= TimeUtil.currentEpochSecond
          }

          val numRemixes = unsigned.inputMaps.count(
            _.witnessUTXOOpt.exists(_.witnessUTXO.value == state.round.amount))
          val numNewEntrants =
            tx.outputs.count(_.value == state.round.amount) - numRemixes

          lazy val expectedAmtBackOpt =
            state.expectedAmtBackOpt(numRemixes, numNewEntrants)

          lazy val hasCorrectChange =
            (expectedAmtBackOpt, state.initDetails.changeSpkOpt) match {
              case (Some(changeAmt), Some(changeSpk)) =>
                val outputOpt =
                  tx.outputs.find(_.scriptPubKey == changeSpk)
                outputOpt.exists(_.value >= changeAmt)
              case (None, None) => true
              case (Some(_), None) =>
                logger.error(
                  "Incorrect state expecting change when having no change address")
                false
              case (None, Some(_)) =>
                logger.error(
                  "Incorrect state has change address when expecting no change")
                false
            }

          lazy val hasMixOutput =
            tx.outputs.contains(state.initDetails.mixOutput)

          lazy val noDust = tx.outputs.forall(_.value >= Policy.dustThreshold)

          lazy val goodFeeRate = {
            if (unsigned.inputMaps.forall(_.witnessUTXOOpt.isDefined)) {
              val inputAmt = unsigned.inputMaps
                .flatMap(_.witnessUTXOOpt)
                .map { utxo =>
                  utxo.witnessUTXO.value
                }
                .sum

              val feeRate =
                SatoshisPerVirtualByte.calc(inputAmt, unsigned.transaction)

              feeRate.toLong >= 1
            } else false
          }

          if (!goodLockTime) {
            Future.failed(
              new BadLocktimeException(
                "Transaction locktime is too far in the future"))
          } else if (!hasMixOutput) {
            Future.failed(new InvalidMixedOutputException(
              s"Missing expected mixed output ${state.initDetails.mixOutput}"))
          } else if (!hasCorrectChange) {
            Future.failed(
              new InvalidChangeOutputException(
                s"Missing expected change output of $expectedAmtBackOpt"))
          } else if (missingInputs.nonEmpty) {
            Future.failed(new MissingInputsException(
              s"Missing inputs from transaction: ${missingInputs.mkString(",")}"))
          } else if (!noDust) {
            Future.failed(
              new DustOutputsException("Transaction contains dust outputs"))
          } else if (!goodFeeRate) {
            Future.failed(
              new TooLowOfFeeException("Transaction has too low of a fee"))
          } else {
            logger.info("PSBT is valid, giving channel peer")
            for {
              // tell peer about funding psbt
              _ <- state.initDetails.nodeIdOpt match {
                case Some(_) =>
                  vortexWallet.completeChannelOpen(state.initDetails.chanId,
                                                   unsigned)
                case None => Future.unit // skip if on-chain
              }
              _ = logger.info("Valid with channel peer, signing")
              // sign to be sent to coordinator
              signed <- vortexWallet.signPSBT(unsigned, inputs)
            } yield {
              roundDetails = state.nextStage(signed)
              signed
            }
          }
        }
    }
  }

  def restartRound(msg: RestartRoundMessage): Future[Unit] = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: InputsRegistered | _: MixOutputRegistered) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot restartRound")
      case state: PSBTSigned =>
        logger.info("Round restarted..")

        val cancelF = state.initDetails.nodeIdOpt match {
          case Some(nodeId) =>
            vortexWallet.cancelChannel(state.channelOutpoint, nodeId)
          case None => Future.unit
        }

        cancelF.map { _ =>
          roundDetails =
            state.restartRound(msg.mixDetails, msg.nonceMessage.schnorrNonce)
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
      case state: PSBTSigned =>
        logger.info("Round complete!!")
        val anonSet = VortexUtils.getAnonymitySet(
          signedTx,
          state.channelOutpoint.vout.toInt)

        logger.info(s"Anonymity Set gained from round: $anonSet")

        for {
          _ <- vortexWallet.broadcastTransaction(signedTx)
          _ <- vortexWallet
            .labelTransaction(signedTx.txId, s"LnVortex Mix Anon set: $anonSet")
            .recover(_ => ())
          _ <- utxoDAO.setAnonSets(state.initDetails.inputs.map(_.outPoint),
                                   state.channelOutpoint,
                                   state.changeOutpointOpt,
                                   anonSet)
          _ <- stop()
          _ <- getNewRound
        } yield ()
    }
  }
}

object VortexClient {
  val knownVersions: Vector[UInt16] = Vector(UInt16.zero)
}
