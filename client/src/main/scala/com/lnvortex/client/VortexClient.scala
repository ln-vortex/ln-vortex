package com.lnvortex.client

import akka.actor.ActorSystem
import com.lnvortex.client.VortexClient.getDummyWitness
import com.lnvortex.client.VortexClientException._
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.client.db._
import com.lnvortex.client.networking._
import com.lnvortex.core.RoundDetails._
import com.lnvortex.core._
import com.lnvortex.core.api._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.currency._
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.protocol._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._

import java.net.InetSocketAddress
import scala.concurrent._
import scala.concurrent.duration._

case class VortexClient[+T <: VortexWalletApi](
    vortexWallet: T,
    coordinatorAddress: CoordinatorAddress)(implicit
    val system: ActorSystem,
    val config: VortexAppConfig)
    extends VortexHttpClient[T]
    with StartStopAsync[Unit]
    with Logging {
  implicit val ec: ExecutionContext = system.dispatcher

  require(coordinatorAddress.network == vortexWallet.network,
          "Coordinator address network must match wallet network")

  lazy val utxoDAO: UTXODAO = UTXODAO()

  private val coordinatorName: String = coordinatorAddress.name

  private var roundDetails: RoundDetails = NoDetails(false)

  private def requeue: Boolean = roundDetails.requeue

  // only used for debugging / testing
  private[client] def setRoundDetails(details: RoundDetails): Unit =
    roundDetails = details

  def getCurrentRoundDetails: RoundDetails = roundDetails

  def setRequeue(bool: Boolean): Unit = {
    logger.debug(s"Setting requeue to: $bool")
    roundDetails = roundDetails.setRequeue(bool)
  }

  private[lnvortex] def setRound(params: RoundParameters): Future[Unit] = {
    if (VortexClient.knownVersions.contains(params.version)) {
      roundDetails match {
        case _: NoDetails | _: ReceivedNonce | _: KnownRound |
            _: InputsScheduled =>
          logger.debug(
            s"Received round parameters from coordinator $coordinatorName")
          logger.trace(s"Round Parameters: $params")
          roundDetails = KnownRound(requeue, params)
          Future.unit
        case state: InitializedRound =>
          // Potential race condition here
          // Retry for a bit to set us to the new state
          AsyncUtil
            .awaitCondition(() => !roundDetails.isInstanceOf[InitializedRound],
                            interval = 100.milliseconds,
                            maxTries = 300)
            .flatMap(_ => setRound(params))
            .recoverWith { _ =>
              Future.failed(
                new IllegalStateException(
                  s"Cannot set new round at state $state"))
            }
      }
    } else {
      Future.failed(new RuntimeException(
        s"Received unknown version ${params.version}, consider updating software"))
    }
  }

  protected def updateFeeRate(feeRate: SatoshisPerVirtualByte): Unit = {
    roundDetails match {
      case details: PendingRoundDetails =>
        logger.debug(
          s"Received new fee rate from coordinator $coordinatorName: $feeRate")
        roundDetails = details.updateFeeRate(feeRate)
        details match {
          case _: KnownRound | _: ReceivedNonce =>
          // Do nothing
          case _: InputsScheduled =>
            // todo verify inputs are okay
            ()
        }
      case state @ (_: NoDetails | _: InitializedRound) =>
        logger.debug(
          s"Received new fee rate, but cannot update at state $state")
        ()
    }
  }

  override def start(): Future[Unit] = {
    for {
      _ <- subscribeRounds(vortexWallet.network)
    } yield ()
  }

  override def stop(): Future[Unit] = {
    disconnect()
  }

  def listCoins(): Future[Vector[UnspentCoin]] = {
    logger.trace("Returning wallet's coins")
    for {
      coins <- vortexWallet.listCoins()
      utxoDbs <- utxoDAO.createOutPointMap(coins.map(_.outPoint))
    } yield {
      coins.map { coin =>
        val utxoOpt = utxoDbs.get(coin.outPoint).flatten
        utxoOpt match {
          case Some(utxo) =>
            coin.copy(anonSet = utxo.anonSet,
                      warning = utxo.warning,
                      isChange = utxo.isChange)
          case None => coin
        }
      }
    }
  }

  def listChannels(): Future[Vector[ChannelDetails]] = {
    logger.trace("Returning wallet's channels")
    for {
      channels <- vortexWallet.listChannels()
      utxoDbs <- utxoDAO.createOutPointMap(channels.map(_.outPoint))
    } yield {
      channels.map { channel =>
        val utxoOpt = utxoDbs.get(channel.outPoint).flatten
        utxoOpt match {
          case Some(utxo) =>
            channel.copy(anonSet = utxo.anonSet)
          case None => channel
        }
      }
    }
  }

  def askNonce(): Future[SchnorrNonce] = {
    logger.info("Asking nonce from coordinator")
    roundDetails match {
      case KnownRound(_, round) =>
        for {
          _ <- startRegistration(round.roundId)
          _ = logger.debug("Awaiting response from coordinator")
          _ <- AsyncUtil
            .awaitCondition(() => getNonceOpt(roundDetails).isDefined,
                            interval = 100.milliseconds,
                            maxTries = 300)
            .recover { case _: Throwable =>
              throw new RuntimeException(
                "Did not receive nonce from coordinator")
            }
        } yield {
          val nonce = getNonceOpt(roundDetails).get
          logger.info(s"Got nonce from coordinator $nonce")
          nonce
        }
      case state @ (_: NoDetails | _: ReceivedNonce | _: InputsScheduled |
          _: InitializedRound) =>
        Future.failed(
          new IllegalStateException(s"Cannot ask nonce at state $state"))
    }
  }

  private[lnvortex] def storeNonce(nonce: SchnorrNonce): Unit = {
    roundDetails match {
      case state @ (_: NoDetails | _: ReceivedNonce | _: InitializedRound |
          _: InputsScheduled) =>
        throw new IllegalStateException(s"Cannot store nonce at state $state")
      case details: KnownRound =>
        roundDetails = details.nextStage(nonce)
    }
  }

  def cancelRegistration(): Future[Unit] = {
    logger.info(s"Canceling registration to $coordinatorName")
    setRequeue(false)

    val originalRound = getCurrentRoundDetails
    getNonceOpt(originalRound) match {
      case Some(nonce) =>
        val roundParams = getRoundParamsOpt(originalRound).get
        val msg = CancelRegistrationMessage(nonce, roundParams.roundId)
        cancelRegistration(msg).flatMap { valid =>
          if (valid) {
            val releaseCoinsF = getInitDetailsOpt(originalRound) match {
              case Some(initDetails) =>
                vortexWallet
                  .releaseCoins(initDetails.inputs)
                  .recover { case err: Throwable =>
                    logger.error(s"Failed to release coins: ${err.getMessage}",
                                 err)
                  }
              case None => Future.unit
            }

            releaseCoinsF.map { _ =>
              logger.info(s"Registration canceled to $coordinatorName")
              roundDetails = KnownRound(requeue = false, roundParams)
            }
          } else {
            Future.failed(
              new RuntimeException(
                s"Registration to $coordinatorName was not canceled"))
          }
        }
      case None =>
        Future.failed(new IllegalStateException("No registration to cancel"))
    }
  }

  private def checkMinChanSize(
      amount: CurrencyUnit,
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Future[Unit] = {
    logger.debug(s"Verifying $nodeId's min chan size is below $amount")
    val connectF = vortexWallet.isConnected(nodeId).flatMap { connected =>
      if (!connected) {
        peerAddrOpt match {
          case None =>
            Future.failed(new IllegalArgumentException(
              s"Not connected to peer $nodeId, you must first connect with them in order to open a channel"))
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
      } yield logger.debug("Peer has a correct minimum channel size")

      f.recover { case ex: Throwable =>
        logger.error(s"Error from Lightning node: ${ex.getMessage}", ex)
        throw new RuntimeException(
          s"$amount is under peer's minimum channel size")
      }
    }
  }

  def getCoinsAndQueue(
      outPoints: Vector[TransactionOutPoint],
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress]): Future[Unit] = {
    require(outPoints.nonEmpty, "Must include inputs")
    listCoins().flatMap { utxos =>
      val coins = utxos
        .filter(u => outPoints.contains(u.outPoint))

      if (coins.exists(_.isChange))
        require(coins.size == 1, "Cannot combine change with other coins")

      val outputRefs = coins.map(_.outputReference)
      queueCoinsAndTryConnect(outputRefs, nodeId, peerAddrOpt)
    }
  }

  def getCoinsAndQueue(
      outPoints: Vector[TransactionOutPoint],
      address: BitcoinAddress): Future[Unit] = {
    require(outPoints.nonEmpty, "Must include inputs")
    listCoins().map { utxos =>
      val coins = utxos
        .filter(u => outPoints.contains(u.outPoint))

      if (coins.exists(_.isChange))
        require(coins.size == 1, "Cannot combine change with other coins")

      val outputRefs = coins.map(_.outputReference)
      queueCoins(outputRefs, address)
    }
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
    logger.trace(s"queueing coins $outputRefs to $nodeId@$peerAddrOpt")
    roundDetails match {
      case state @ (_: NoDetails | _: KnownRound | _: InputsScheduled |
          _: InputsRegistered | _: OutputRegistered | _: PSBTSigned) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot queue coins")
      case receivedNonce: ReceivedNonce =>
        val round = receivedNonce.round

        val isRemix =
          outputRefs.size == 1 && outputRefs.head.output.value == round.amount
        val target = round.getTargetAmount(isRemix, outputRefs.size)

        logger.info(
          s"Queueing ${outputRefs.size} coins to open a channel to $nodeId")
        // todo check if peer supports taproot channel if needed
        if (
          !outputRefs.forall(
            _.output.scriptPubKey.scriptType == round.inputType)
        ) {
          throw new InvalidInputException(
            s"Error. Must use ${round.inputType} inputs")
        } else if (outputRefs.map(_.output.value).sum < round.amount) {
          throw new InvalidInputException(
            s"Must select more inputs to find round, needed ${round.amount}")
        } else if (round.outputType != ScriptType.WITNESS_V0_SCRIPTHASH) {
          throw new InvalidTargetOutputException(
            "This version of LND only supports SegwitV0 channels")
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
    logger.trace(s"queueing coins $outputRefs to $address")
    roundDetails match {
      case state @ (_: NoDetails | _: KnownRound | _: InputsScheduled |
          _: InputsRegistered | _: OutputRegistered | _: PSBTSigned) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot queue coins")
      case receivedNonce: ReceivedNonce =>
        val round = receivedNonce.round

        val isRemix =
          outputRefs.size == 1 && outputRefs.head.output.value == round.amount
        val target = round.getTargetAmount(isRemix, outputRefs.size)

        logger.info(
          s"Queueing ${outputRefs.size} coins for collaborative transaction")
        if (
          !outputRefs.forall(
            _.output.scriptPubKey.scriptType == round.inputType)
        ) {
          throw new InvalidInputException(
            s"Error. Must use ${round.inputType} inputs")
        } else if (address.scriptPubKey.scriptType != round.outputType) {
          throw new InvalidTargetOutputException(
            s"Error. Must use ${round.outputType} address")
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
      case state @ (_: NoDetails | _: InitializedRound) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot register coins"))
      case _: KnownRound | _: ReceivedNonce =>
        // Sometimes we get the AskInputs before the round details + nonce,
        // so we need to wait for the round details to be set.
        logger.debug(
          "Received AskInputs before expected, waiting until inputs are scheduled")
        AsyncUtil
          .awaitCondition(
            () => roundDetails.status == ClientStatus.InputsScheduled,
            interval = 100.milliseconds,
            maxTries = 300)
          .recover { _ =>
            logger.debug("Inputs were never scheduled")
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
        val changeAmt =
          selectedAmt - round.amount - round.coordinatorFee - onChainFees

        val needsChange = changeAmt >= Policy.dustThreshold

        if (logger.isDebugEnabled && needsChange) {
          logger.debug(s"Calculated change amount: $changeAmt")
        } else if (logger.isDebugEnabled) {
          logger.debug("No change will be used for this round")
        }

        // Reserve utxos until 10 minutes after the round is scheduled.
        val timeUtilRound = round.time + 600 - TimeUtil.currentEpochSecond

        logger.debug("Creating input proofs")
        for {
          inputProofs <- FutureUtil.sequentially(outputRefs)(
            vortexWallet
              .createInputProof(scheduled.nonce, _, timeUtilRound.seconds))

          inputRefs = outputRefs.zip(inputProofs).map { case (outRef, proof) =>
            InputReference(outRef, proof)
          }

          changeAddrOpt <-
            if (needsChange) {
              logger.debug("Generating change address")
              vortexWallet.getChangeAddress(round.changeType).map(Some(_))
            } else FutureUtil.none

          channelDetails <- scheduled.nodeIdOpt match {
            case Some(nodeId) =>
              logger.debug(s"Initializing channel open to $nodeId")
              vortexWallet
                .initChannelOpen(nodeId = nodeId,
                                 peerAddrOpt = scheduled.peerAddrOpt,
                                 fundingAmount = scheduled.round.amount,
                                 privateChannel = true)
                .map { details =>
                  if (details.scriptType != round.outputType) {
                    throw new InvalidTargetOutputException(
                      s"The channel is invalid, need a ${round.outputType} output")
                  } else details
                }
            case None =>
              val addressF = scheduled.addressOpt match {
                case Some(addr) =>
                  if (addr.scriptPubKey.scriptType == round.outputType) {
                    Future.successful(addr)
                  } else {
                    Future.failed(new InvalidTargetOutputException(
                      s"Need a ${round.outputType} address, got ${addr.scriptPubKey.scriptType}"))
                  }
                case None =>
                  logger.debug("Generating address for round")
                  vortexWallet.getNewAddress(round.outputType)
              }

              addressF.map { addr =>
                val id = CryptoUtil.sha256(addr.value).bytes
                OutputDetails(id, scheduled.round.amount, addr)
              }
          }
        } yield {
          val targetOutput = channelDetails.output

          logger.debug("Generating blinding details...")
          val hashedOutput =
            RegisterOutput.calculateChallenge(targetOutput, round.roundId)

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
            targetOutput = targetOutput,
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

  def processBlindOutputSig(blindOutputSig: FieldElement): RegisterOutput = {
    logger.info("Got blind signature from coordinator, processing...")
    roundDetails match {
      case state @ (_: NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: OutputRegistered | _: PSBTSigned) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot processBlindOutputSig")
      case details: InputsRegistered =>
        val targetOutput = details.initDetails.targetOutput
        val publicKey = details.round.publicKey
        val nonce = details.nonce
        val tweaks = details.initDetails.tweaks

        val challenge =
          RegisterOutput.calculateChallenge(targetOutput, details.round.roundId)

        logger.debug("Unblinding signature from coordinator")
        val sig = BlindSchnorrUtil.unblindSignature(blindSig = blindOutputSig,
                                                    signerPubKey = publicKey,
                                                    signerNonce = nonce,
                                                    blindingTweaks = tweaks,
                                                    message = challenge)
        roundDetails = details.nextStage

        RegisterOutput(sig, targetOutput)
    }
  }

  private[client] def sendOutputMessageAsBob(
      msg: RegisterOutput): Future[Unit] = {
    logger.info("Sending channel output as Bob")
    for {
      valid <- registerOutput(msg)
    } yield {
      if (!valid) {
        throw new RuntimeException(
          "Coordinator told us our output was invalid!")
      }
    }
  }

  def validateAndSignPsbt(unsigned: PSBT): Future[PSBT] = {
    logger.info("Received unsigned PSBT from coordinator, verifying...")
    roundDetails match {
      case state @ (_: NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: InputsRegistered | _: PSBTSigned) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot validateAndSignPsbt"))
      case state: OutputRegistered =>
        logger.trace("Getting current block height")
        vortexWallet.getBlockHeight().flatMap { height =>
          val inputs: Vector[OutputReference] = state.initDetails.inputs
          lazy val myOutpoints = inputs.map(_.outPoint)
          val tx = unsigned.transaction
          lazy val txOutpoints = tx.inputs.map(_.previousOutput)
          // should we care if they don't have our inputs?
          lazy val missingInputs = myOutpoints.filterNot(txOutpoints.contains)

          lazy val correctInputTypes =
            if (unsigned.inputMaps.forall(_.witnessUTXOOpt.isDefined)) {
              unsigned.inputMaps.flatMap(_.witnessUTXOOpt).forall { txOut =>
                txOut.witnessUTXO.scriptPubKey.scriptType == state.round.inputType
              }
            } else false

          lazy val correctOutputTypes =
            unsigned.transaction.outputs.forall { txOut =>
              val scriptType = txOut.scriptPubKey.scriptType
              scriptType == state.round.outputType || scriptType == state.round.changeType
            }

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

          lazy val expectedAmtBack =
            state.expectedAmtBack(numRemixes, numNewEntrants)

          lazy val changeOutputOpt =
            state.initDetails.changeSpkOpt.flatMap { changeSpk =>
              tx.outputs.find(_.scriptPubKey == changeSpk)
            }

          lazy val targetOutput =
            tx.outputs.contains(state.initDetails.targetOutput)

          lazy val noDust = tx.outputs.forall(_.value >= Policy.dustThreshold)

          lazy val estimateFeeRate = {
            logger.debug("Verifying transaction has a valid fee rate")
            val inputAmt = unsigned.inputMaps
              .flatMap(_.witnessUTXOOpt)
              .map(_.witnessUTXO.value)
              .sum

            // add fake signatures so we can correctly estimate the size
            logger.trace("Adding fake signatures to transaction")
            val signedPsbt =
              unsigned.inputMaps.zipWithIndex.foldLeft(unsigned) {
                case (psbt, (_, index)) =>
                  val (scriptSig, witOpt) =
                    getDummyWitness(state.round.inputType)
                  witOpt match {
                    case Some(witness) =>
                      psbt.addFinalizedScriptWitnessToInput(scriptSig,
                                                            witness,
                                                            index)
                    case None =>
                      psbt.addFinalizedScriptWitnessToInput(scriptSig,
                                                            EmptyScriptWitness,
                                                            index)
                  }
              }

            val dummySigned = signedPsbt.extractTransaction

            SatoshisPerVirtualByte.calc(inputAmt, dummySigned)
          }

          if (!goodLockTime) {
            Future.failed(
              new BadLocktimeException(
                "Transaction locktime is too far in the future"))
          } else if (!targetOutput) {
            Future.failed(new InvalidTargetOutputException(
              s"Missing expected target output ${state.initDetails.targetOutput}"))
          } else if (
            expectedAmtBack != Satoshis.zero && !changeOutputOpt.exists(
              _.value >= expectedAmtBack)
          ) {
            Future.failed(new InvalidChangeOutputException(
              s"Missing expected change output of $expectedAmtBack, got ${changeOutputOpt
                  .map(_.value)}"))
          } else if (missingInputs.nonEmpty) {
            Future.failed(new MissingInputsException(
              s"Missing inputs from transaction: ${missingInputs.mkString(",")}"))
          } else if (!noDust) {
            Future.failed(
              new DustOutputsException("Transaction contains dust outputs"))
          } else if (!correctInputTypes) {
            Future.failed(
              new InvalidInputTypeException(
                s"Expected input type ${state.round.inputType}"))
          } else if (!correctOutputTypes) {
            Future.failed(new InvalidOutputTypeException(
              s"Expected output type ${state.round.outputType} & ${state.round.changeType}"))
          } else if (estimateFeeRate.toLong < 1) {
            Future.failed(
              new TooLowOfFeeException(
                s"Transaction has a too low fee, $estimateFeeRate"))
          } else {
            logger.info("PSBT is valid, giving channel peer...")
            for {
              // tell peer about funding psbt
              _ <- state.initDetails.nodeIdOpt match {
                case Some(_) =>
                  vortexWallet.completeChannelOpen(state.initDetails.chanId,
                                                   unsigned)
                case None => Future.unit // skip if on-chain
              }
              _ = logger.info("Valid with channel peer, signing...")
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
      case state @ (_: NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: InputsRegistered | _: OutputRegistered) =>
        throw new IllegalStateException(
          s"At invalid state $state, cannot restartRound")
      case state: PSBTSigned =>
        logger.info("Round restarted...")

        val cancelF = state.initDetails.nodeIdOpt match {
          case Some(nodeId) =>
            // We need to cancel the channel because the peer expects the
            // channel from the transaction we just showed them, but is
            // now not going to be signed.
            logger.debug("Canceling lightning channel from round")
            vortexWallet.cancelChannel(state.targetOutpoint, nodeId)
          case None => Future.unit
        }

        cancelF.map { _ =>
          roundDetails =
            state.restartRound(msg.roundParams, msg.nonceMessage.schnorrNonce)
        }
    }
  }

  def completeRound(signedTx: Transaction): Future[Unit] = {
    roundDetails match {
      case state @ (_: NoDetails | _: KnownRound | _: ReceivedNonce |
          _: InputsScheduled | _: InputsRegistered | _: OutputRegistered) =>
        Future.failed(
          new IllegalStateException(
            s"At invalid state $state, cannot completeRound"))
      case state: PSBTSigned =>
        logger.info("Round complete!")
        val anonSet =
          VortexUtils.getAnonymitySet(signedTx, state.targetOutpoint.vout.toInt)

        logger.info(s"Anonymity Set gained from round: $anonSet")

        // Recover failed broadcasts because we should expect
        // the coordinator or one of the other clients to broadcast.
        // We still want to label the transaction and account for it in
        // our database to update anon sets.
        val broadcastF = vortexWallet
          .broadcastTransaction(signedTx)
          .recover { _ =>
            logger.warn(
              s"Failed to broadcast transaction: ${signedTx.txIdBE.hex}")
            logger.debug(s"Failed to broadcast transaction: ${signedTx.hex}")
          }

        val completeRoundF = for {
          _ <- broadcastF
          _ <- vortexWallet
            .labelTransaction(signedTx.txId, s"Vortex Anonymity set: $anonSet")
            .recover(_ => ())
          _ <- utxoDAO.setAnonSets(state, anonSet)
          _ <- disconnectRegistration()
          _ = roundDetails = NoDetails(requeue)
        } yield ()

        val requeueF = completeRoundF.flatMap { _ =>
          if (requeue) {
            logger.info("Requeueing round...")
            // Wait until we have the new round params
            AsyncUtil
              .awaitCondition(
                () => getCurrentRoundDetails.status != ClientStatus.NoDetails,
                interval = 100.milliseconds,
                maxTries = 300)
              .flatMap { _ =>
                logger.debug("Received new round params, requeueing round...")
                for {
                  _ <- askNonce()
                  addr <- vortexWallet.getNewAddress(state.round.outputType)
                  _ <- getCoinsAndQueue(Vector(state.targetOutpoint), addr)
                } yield logger.info("Requeueing round complete!")
              }
          } else {
            Future.unit
          }
        }

        requeueF.failed.foreach { err =>
          err.printStackTrace()
          logger.error(s"Failed to requeue round: ${err.getMessage}", err)
        }

        completeRoundF
    }
  }
}

object VortexClient {
  val knownVersions: Vector[Int] = Vector(0)

  // $COVERAGE-OFF
  def getDummyWitness(
      scriptType: ScriptType): (ScriptSignature, Option[ScriptWitness]) = {
    scriptType match {
      case tpe @ (ScriptType.NONSTANDARD | ScriptType.MULTISIG |
          ScriptType.CLTV | ScriptType.CSV |
          ScriptType.NONSTANDARD_IF_CONDITIONAL |
          ScriptType.NOT_IF_CONDITIONAL | ScriptType.MULTISIG_WITH_TIMEOUT |
          ScriptType.PUBKEY_WITH_TIMEOUT | ScriptType.NULLDATA |
          ScriptType.WITNESS_UNKNOWN | ScriptType.WITNESS_COMMITMENT |
          ScriptType.WITNESS_V0_SCRIPTHASH) =>
        throw new IllegalArgumentException(s"Unknown address type $tpe")
      case ScriptType.PUBKEY =>
        val sig = DummyECDigitalSignature
        (P2PKScriptSignature(sig), None)
      case ScriptType.PUBKEYHASH =>
        val sig = DummyECDigitalSignature
        (P2PKHScriptSignature(sig, ECPublicKey.freshPublicKey), None)
      case ScriptType.SCRIPTHASH =>
        val key = ECPublicKey.freshPublicKey
        val dummySpk = P2WPKHWitnessSPKV0(key)
        val dummyScriptSig = P2SHScriptSignature(dummySpk)
        val dummyWit = P2WPKHWitnessV0(key, DummyECDigitalSignature)

        (dummyScriptSig, Some(dummyWit))
      case ScriptType.WITNESS_V0_KEYHASH =>
        val scriptSig = EmptyScriptSignature
        val key = ECPublicKey.freshPublicKey
        val dummyWit = P2WPKHWitnessV0(key, DummyECDigitalSignature)

        (scriptSig, Some(dummyWit))
      case ScriptType.WITNESS_V1_TAPROOT =>
        val scriptSig = EmptyScriptSignature
        val dummyWit = TaprootKeyPath(SchnorrDigitalSignature.dummy)

        (scriptSig, Some(dummyWit))
    }
  }
  // $COVERAGE-ON
}
