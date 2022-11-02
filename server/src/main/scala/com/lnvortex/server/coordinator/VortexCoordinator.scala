package com.lnvortex.server.coordinator

import akka.actor._
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl._
import com.lnvortex.core.RoundStatus._
import com.lnvortex.core._
import com.lnvortex.server.VortexServerException
import com.lnvortex.server.VortexServerException._
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.internal._
import com.lnvortex.server.models._
import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddressType
import org.bitcoins.core.config._
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util._
import org.bitcoins.core.wallet.builder._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import play.api.libs.json.Json
import slick.dbio._

import java.time.Instant
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

class VortexCoordinator private (
    private[coordinator] val km: CoordinatorKeyManager,
    val bitcoind: BitcoindRpcClient,
    val roundId: DoubleSha256Digest,
    var currentFeeRate: SatoshisPerVirtualByte,
    roundStartTime: Long)(implicit
    system: ActorSystem,
    val config: VortexCoordinatorAppConfig)
    extends StartStop[Unit]
    with PeerValidation
    with VortexUtils
    with Logging {
  implicit val ec: ExecutionContext = system.dispatcher

  require(bitcoind.instance.network == config.network,
          "Bitcoind on different network")

  final val version = 0

  private[server] val bannedUtxoDAO = BannedUtxoDAO()
  private[server] val aliceDAO = AliceDAO()
  private[server] val inputsDAO = RegisteredInputDAO()
  private[server] val outputsDAO = RegisteredOutputDAO()
  private[server] val roundDAO = RoundDAO()

  private val safeDatabase = aliceDAO.safeDatabase

  lazy val publicKey: SchnorrPublicKey = km.publicKey

  private val nextCoordinatorP: Promise[VortexCoordinator] =
    Promise[VortexCoordinator]()

  def getNextCoordinator: Future[VortexCoordinator] = nextCoordinatorP.future

  private val askInputsP: Promise[AskInputs] = Promise[AskInputs]()

  def getAskInputsMessage: Future[AskInputs] = askInputsP.future

  def getCurrentRoundId: DoubleSha256Digest = roundId

  private def roundAddressLabel: String = s"Vortex Round: ${roundId.hex}"

  private[server] def inputFee(
      feeRate: SatoshisPerVirtualByte): CurrencyUnit = {
    FeeCalculator.inputFee(feeRate, config.inputScriptType)
  }

  private[server] def outputFee(
      feeRate: SatoshisPerVirtualByte,
      numPeersOpt: Option[Int] = Some(config.minPeers)): CurrencyUnit = {
    FeeCalculator.outputFee(feeRate = feeRate,
                            outputScriptType = config.outputScriptType,
                            coordinatorScriptType = config.changeScriptType,
                            numPeersOpt = numPeersOpt)
  }

  private[server] def changeOutputFee(
      feeRate: SatoshisPerVirtualByte): CurrencyUnit = {
    FeeCalculator.changeOutputFee(feeRate, config.changeScriptType)
  }

  private[server] def sendUpdatedFeeRate(): Future[SatoshisPerVirtualByte] = {
    logger.debug("Updating fee rate...")
    config.fetchFeeRate().map { feeRate =>
      if (feeRate != currentFeeRate) {
        currentFeeRate = feeRate
        logger.info(s"Updated fee rate to $feeRate")
        logger.debug(s"Announcing fee rate to peers")
        roundSubscribers.foreach { queue =>
          queue
            .offer(TextMessage(Json.toJson(FeeRateHint(feeRate)).toString))
            .map(_ => logger.trace(s"Announced fee rate to peer"))
            .recover { _ =>
              roundSubscribers -= queue
              ()
            }
        }

        feeRate
      } else currentFeeRate
    }
  }

  private lazy val feeRateHintsCancellable: Cancellable = {
    if (roundStartTime != 0L) {
      val interval = (config.roundInterval.toSeconds / 10).seconds
      system.scheduler.scheduleAtFixedRate(interval, interval) { () =>
        // once round has started, we don't need to update the fee rate hints
        if (!beginInputRegistrationCancellable.isCancelled) {
          val _ = sendUpdatedFeeRate()
        }
      }
    } else {
      throw new RuntimeException(
        "Attempt to start input coordinator with invalid params")
    }
  }

  // switch to input registration at correct time
  private lazy val beginInputRegistrationCancellable: Cancellable = {
    if (roundStartTime != 0L)
      system.scheduler.scheduleOnce(config.roundInterval) {
        beginInputRegistration()
          .map(_ => ())
          .recoverWith { _ =>
            cancelRound().map(_ => ())
          }
        ()
      }
    else {
      throw new RuntimeException(
        "Attempt to start input coordinator with invalid params")
    }
  }

  private var beginOutputRegistrationCancellable: Option[Cancellable] =
    None

  private var sendPSBTCancellable: Option[Cancellable] =
    None

  def roundParams: RoundParameters =
    RoundParameters(
      version = version,
      roundId = roundId,
      amount = config.roundAmount,
      coordinatorFee = config.coordinatorFee,
      publicKey = publicKey,
      time = roundStartTime,
      minPeers = config.minPeers,
      maxPeers = config.maxPeers,
      inputType = config.inputScriptType,
      outputType = config.outputScriptType,
      changeType = config.changeScriptType,
      status = config.statusString,
      title = config.titleOpt,
      feeRate = currentFeeRate
    )

  private[lnvortex] val roundSubscribers =
    mutable.ListBuffer[SourceQueueWithComplete[Message]]()

  private[lnvortex] val connectionHandlerMap: mutable.Map[
    Sha256Digest,
    SourceQueueWithComplete[Message]] =
    mutable.Map.empty

  private val inputsRegisteredP: Promise[Unit] = Promise[Unit]()
  private val outputsRegisteredP: Promise[Unit] = Promise[Unit]()

  private val signedPeersMap: mutable.Map[Sha256Digest, Boolean] =
    mutable.Map.empty

  private val completedTxP: Promise[Transaction] =
    Promise[Transaction]()

  def getCompletedTx: Future[Transaction] = completedTxP.future

  private def disconnectPeers(): Unit = {
    if (connectionHandlerMap.values.nonEmpty) {
      logger.info("Disconnecting peers")
      connectionHandlerMap.values.foreach { peer =>
        peer.complete()
      }
    }
  }

  def currentRound(): Future[RoundDb] = {
    getRound(roundId)
  }

  def getRound(roundId: DoubleSha256Digest): Future[RoundDb] = {
    roundDAO.safeDatabase.run(getRoundAction(roundId))
  }

  def currentRoundAction(): DBIOAction[RoundDb, NoStream, Effect.Read] = {
    getRoundAction(roundId)
  }

  def getRoundAction(roundId: DoubleSha256Digest): DBIOAction[RoundDb,
                                                              NoStream,
                                                              Effect.Read] = {
    roundDAO.findByPrimaryKeyAction(roundId).map {
      case Some(db) => db
      case None =>
        throw new RuntimeException(
          s"Could not find a round db for roundId $roundId")
    }
  }

  private[lnvortex] def beginInputRegistration(): Future[AskInputs] = {
    if (
      !beginInputRegistrationCancellable.isCancelled &&
      beginInputRegistrationCancellable.cancel()
    ) {
      logger.info("Starting input registration")
      Try(feeRateHintsCancellable.cancel())

      val feeRateF = config.network match {
        case MainNet | TestNet3 | SigNet =>
          // Wait 3 seconds to help prevent race conditions in clients
          // but only in live environments
          val duration = 3.seconds
          logger.debug(s"Waiting $duration until fetching fee rate")
          AsyncUtil
            .nonBlockingSleep(duration)
            .flatMap { _ =>
              logger.debug("Fetching fee rate")
              config.fetchFeeRate()
            }
        case RegTest =>
          logger.debug("Fetching fee rate")
          config.fetchFeeRate()
      }

      feeRateF.flatMap { feeRate =>
        currentFeeRate = feeRate

        val action = for {
          roundDb <- currentRoundAction()
          _ = logger.trace("Updating round status and fee rate")
          updated = roundDb.copy(status = RegisterAlices,
                                 feeRate = Some(feeRate))
          _ <- roundDAO.updateAction(updated)
          alices <- aliceDAO.findByRoundIdAction(roundDb.roundId)
        } yield alices.size

        safeDatabase.run(action).flatMap { numAlices =>
          if (numAlices >= config.minPeers) {
            // Create Output Registration timer
            logger.debug("Scheduling input registration timeout")
            beginOutputRegistrationCancellable = Some(
              system.scheduler.scheduleOnce(config.inputRegistrationTime) {
                inputsRegisteredP.trySuccess(())
                ()
              }
            )

            logger.info("Sending AskInputs to peers")
            val msg =
              AskInputs(roundId,
                        inputFee(feeRate),
                        outputFee(feeRate),
                        changeOutputFee(feeRate))
            // send messages async
            val parallelism = FutureUtil.getParallelism
            Source(connectionHandlerMap.toVector)
              .mapAsync(parallelism = parallelism) { case (id, peer) =>
                logger.trace(s"Sending AskInputs to peer ${id.hex}")
                peer
                  .offer(TextMessage(Json.toJson(msg).toString))
                  .map(_ => ())
                  .recover { ex =>
                    logger.error(s"Failed to send AskInputs to peer ${id.hex}",
                                 ex)
                  }
              }
              .run()
              .map { _ =>
                askInputsP.trySuccess(msg)
                msg
              }
          } else {
            Future.failed(
              new RuntimeException("Not enough peers registered. " +
                s"Need ${config.minPeers} but only have $numAlices"))
          }
        }
      }
    } else {
      Future.failed(new RuntimeException("Input registration already started"))
    }
  }

  private[lnvortex] def beginOutputRegistration(): Future[Unit] = {
    logger.info("Starting output registration")

    beginOutputRegistrationCancellable.foreach(_.cancel())
    beginOutputRegistrationCancellable = None

    val action = for {
      roundDb <- currentRoundAction()
      _ = logger.trace("Updating round status")
      updated = roundDb.copy(status = RegisterOutputs)
      _ <- roundDAO.updateAction(updated)

      _ = logger.trace("Getting alices registered for current round")
      alices <- aliceDAO.findRegisteredForRoundAction(roundId)
    } yield {
      val numRemixes = alices.count(_.isRemix)
      val numNewEntrants = alices.count(!_.isRemix)

      logger.trace(
        s"Found $numRemixes remixes and $numNewEntrants new entrants")

      if (numRemixes < config.minRemixPeers) {
        throw new RuntimeException(
          s"Not enough remixes: $numRemixes < ${config.minRemixPeers}")
      } else if (numNewEntrants < config.minNewPeers) {
        throw new RuntimeException(
          s"Not enough new entrants: $numNewEntrants < ${config.minNewPeers}")
      }

      logger.debug("Scheduling output registration timeout")
      sendPSBTCancellable = Some(
        system.scheduler.scheduleOnce(config.outputRegistrationTime) {
          outputsRegisteredP.trySuccess(())
          ()
        })

      alices
    }

    safeDatabase.run(action).flatMap { alices =>
      logger.info(s"Sending blinded sigs to ${alices.size} peers")
      val peerSigMap = alices.map(t => (t.peerId, t.blindOutputSigOpt.get))
      val sendFs = peerSigMap.map { case (peerId, sig) =>
        connectionHandlerMap.get(peerId) match {
          case Some(peer) =>
            logger.debug(s"Sending blinded sig to ${peerId.hex}")
            val msg = TextMessage(Json.toJson(BlindedSig(sig)).toString)

            peer.offer(msg).map(_ => ()).recover { ex =>
              logger.error(s"Failed to send blinded sig to ${peerId.hex}: ", ex)
            }
          case None => Future.unit
        }
      }
      Future.sequence(sendFs).map(_ => ())
    }
  }

  private[lnvortex] def sendUnsignedPSBT(): Future[PSBT] = {
    logger.info("Sending unsigned PSBT to peers")

    sendPSBTCancellable.foreach(_.cancel())
    sendPSBTCancellable = None

    // $COVERAGE-OFF$
    val addressType = config.changeScriptType match {
      case tpe @ (ScriptType.NONSTANDARD | ScriptType.MULTISIG |
          ScriptType.CLTV | ScriptType.CSV |
          ScriptType.NONSTANDARD_IF_CONDITIONAL |
          ScriptType.NOT_IF_CONDITIONAL | ScriptType.MULTISIG_WITH_TIMEOUT |
          ScriptType.PUBKEY_WITH_TIMEOUT | ScriptType.NULLDATA |
          ScriptType.WITNESS_UNKNOWN | ScriptType.WITNESS_COMMITMENT) =>
        throw new IllegalArgumentException(s"Unknown address type $tpe")
      case ScriptType.PUBKEY                => AddressType.Legacy
      case ScriptType.PUBKEYHASH            => AddressType.Legacy
      case ScriptType.SCRIPTHASH            => AddressType.P2SHSegwit
      case ScriptType.WITNESS_V0_KEYHASH    => AddressType.Bech32
      case ScriptType.WITNESS_V0_SCRIPTHASH => AddressType.Bech32m
      case ScriptType.WITNESS_V1_TAPROOT    => AddressType.Bech32m
    }
    // $COVERAGE-ON$

    logger.debug("Generating fee address")
    for {
      addr <- bitcoind.getNewAddress(roundAddressLabel, addressType)
      psbt <- constructUnsignedPSBT(addr)

      msg = TextMessage(Json.toJson(UnsignedPsbtMessage(psbt)).toString)
      _ = logger.info("Sending psbt to peers")
      sendFs = connectionHandlerMap.map { case (id, peer) =>
        logger.trace(s"Sending UnsignedPsbtMessage to peer ${id.hex}")
        peer.offer(msg).map(_ => ()).recover { ex =>
          logger.error(s"Failed to send unsigned psbt to ${id.hex}: ", ex)
        }
      }
      _ <- Future.sequence(sendFs)

      _ = logger.info("Awaiting signatures from peers")
      _ <- AsyncUtil
        .awaitCondition(() => signedPeersMap.forall(_._2),
                        1.second,
                        config.signingTime.toSeconds.toInt)
        .recoverWith { case _: TimeoutException =>
          logger.error("Timed out waiting for all peers to sign")
          Future.failed(
            new RuntimeException("Timed out waiting for all peers to sign"))
        }
      _ = logger.info("Received signatures from all peers")

      alices <- aliceDAO.findSignedForRound(roundId)

      _ = logger.info("Constructing final transaction")
      signedT = Try {
        val psbts = alices.flatMap(_.signedPSBT)

        val combined = psbts.reduce(_ combinePSBT _)
        combined.extractTransactionAndValidate
      }.flatten

      _ = logger.trace(s"Final transaction: ${signedT.map(_.hex)}")

      // make promise complete
      _ = completedTxP.tryComplete(signedT)
    } yield psbt
  }

  private[lnvortex] def onCompletedTransaction(
      tx: Transaction): Future[Unit] = {
    logger.info(s"Broadcasting transaction ${tx.txIdBE.hex}")
    val f = for {
      _ <- bitcoind.sendRawTransaction(tx)
      _ = logger.debug(s"Broadcasted transaction ${tx.txIdBE.hex}")

      msg = SignedTxMessage(tx)

      _ = logger.debug(s"Sending SignedTxMessage to peers")
      // Announce to peers
      sendFs = connectionHandlerMap.map { case (peerId, peer) =>
        logger.trace(s"Sending SignedTxMessage to peer ${peerId.hex}")
        peer
          .offer(TextMessage(Json.toJson(msg).toString))
          .map(_ => ())
          .recover { e =>
            logger.error(s"Error sending signed tx to ${peerId.hex}", e)
          }
      }

      _ = logger.debug(s"Calculating coordinator profit")
      addrs <- bitcoind.listReceivedByAddress(confirmations = 0,
                                              includeEmpty = false,
                                              includeWatchOnly = true,
                                              walletNameOpt = None)
      addrOpt = addrs.find(_.label == roundAddressLabel)
      profitOpt = addrOpt.map(_.amount)
      profit = profitOpt.getOrElse(Satoshis.zero)

      _ = logger.info(s"Updating round with profit $profit")
      action = currentRoundAction().flatMap { roundDb =>
        val updatedRoundDb = roundDb.completeRound(tx, profit)
        roundDAO.updateAction(updatedRoundDb)
      }

      roundDb <- safeDatabase.run(action)
      _ <- Future.sequence(sendFs)

      _ = logger.info("Round complete!")
      _ <- VortexCoordinator.nextRound(this)

      // execute callback after new round is created so we can
      // safely read data from the database
      _ <- config.callBacks.executeOnRoundComplete(roundDb)
    } yield ()

    f.failed.foreach { err =>
      err.printStackTrace()
      logger.error("Error completing round", err)
    }

    f
  }

  private def getNonceAction(
      peerId: Sha256Digest,
      peerRoundId: DoubleSha256Digest): DBIOAction[AliceDb,
                                                   NoStream,
                                                   Effect.Read
                                                     with Effect.Write] = {
    logger.info(s"Alice ${peerId.hex} asked for a nonce")
    require(
      peerRoundId == getCurrentRoundId,
      s"Alice asked for nonce of a different roundId ${peerRoundId.hex} != ${getCurrentRoundId.hex}")

    aliceDAO.findByPrimaryKeyAction(peerId).flatMap {
      case Some(alice) =>
        if (alice.roundId == peerRoundId) {
          logger.trace("Already registered alice, giving same nonce")
          DBIO.successful(alice)
        } else {
          DBIO.failed(new RuntimeException(
            s"Alice ${peerId.hex} asked for nonce of round ${peerRoundId.hex} but is in round ${alice.roundId.hex}"))
        }
      case None =>
        for {
          (nonce, path) <- km.nextNonce()

          aliceDb = AliceDbs.newAlice(peerId, roundId, path, nonce)

          db <- aliceDAO.createAction(aliceDb)
        } yield db
    }
  }

  def getNonce(
      peerId: Sha256Digest,
      connectionHandler: SourceQueueWithComplete[Message],
      peerRoundId: DoubleSha256Digest): Future[AliceDb] = {
    safeDatabase.run(getNonceAction(peerId, peerRoundId)).map { db =>
      logger.debug("Adding alice to connection handler map")
      connectionHandlerMap.put(peerId, connectionHandler)
      db
    }
  }

  def checkBeginInputRegistration(): Future[Option[AskInputs]] = {
    // Call this synchronized so that we don't send AskInputs twice
    synchronized {
      logger.debug("Checking if we should begin input registration")
      if (
        connectionHandlerMap.values.size >= config.maxPeers
        && !beginInputRegistrationCancellable.isCancelled
      ) {
        beginInputRegistration().map(Some(_))
      } else FutureUtil.none
    }
  }

  def cancelRegistration(
      either: Either[SchnorrNonce, Sha256Digest],
      roundId: DoubleSha256Digest): Future[Unit] = {
    if (this.roundId == roundId) {
      logger.debug(s"Canceling registration for peer $either")

      val action = for {
        aliceDbOpt <- aliceDAO.findByEitherAction(either)
        aliceDb = aliceDbOpt match {
          case Some(db) =>
            require(db.roundId == roundId,
                    s"Wrong roundId ${db.roundId.hex} != ${roundId.hex}")
            db
          case None =>
            throw new IllegalArgumentException(s"No alice found with $either")
        }
        _ <- inputsDAO.deleteByPeerIdAction(aliceDb.peerId, aliceDb.roundId)
        _ <- aliceDAO.deleteAction(aliceDb)
        _ = signedPeersMap.remove(aliceDb.peerId)
        _ = connectionHandlerMap.remove(aliceDb.peerId)
      } yield logger.info(s"Alice ${aliceDb.peerId.hex} canceled registration")

      safeDatabase.run(action)
    } else Future.unit
  }

  private[lnvortex] def registerAlice(
      peerId: Sha256Digest,
      registerInputs: RegisterInputs): Future[FieldElement] = {
    logger.info(s"Alice ${peerId.hex} is registering inputs")
    logger.debug(s"Alice's ${peerId.hex} inputs $registerInputs")

    val roundDbA = currentRoundAction().map { roundDb =>
      if (roundDb.status != RegisterAlices)
        throw new IllegalStateException(
          s"Round in incorrect state ${roundDb.status}")
      roundDb
    }

    val isRemixF = if (registerInputs.inputs.size == 1) {
      logger.debug("Checking if alice is remixing")
      val inputRef = registerInputs.inputs.head
      bitcoind
        .getRawTransactionRaw(inputRef.outPoint.txIdBE)
        .map { tx =>
          Try {
            // Make sure this was previously in a coinjoin
            val anonSet = getAnonymitySet(tx, inputRef.outPoint.vout.toInt)
            // make sure it wasn't a change output
            val wasTargetUtxo = inputRef.output.value == config.roundAmount
            val noChange = registerInputs.changeSpkOpt.isEmpty
            anonSet > 1 && wasTargetUtxo && noChange
          }.getOrElse(false)
        }
        .recover(_ => false)
    } else Future.successful(false)

    val action = for {
      roundDb <- roundDbA
      aliceDb <- aliceDAO.findByPrimaryKeyAction(peerId)
      otherInputDbs <- inputsDAO.findByRoundIdAction(roundDb.roundId)
    } yield (roundDb, aliceDb, otherInputDbs)

    val dbF = safeDatabase.run(action)

    val init: Option[InvalidInputsException] = None
    val validInputsF = FutureUtil.foldLeftAsync(init, registerInputs.inputs) {
      case (accum, inputRef) =>
        if (accum.isEmpty) {
          for {
            (_, aliceDbOpt, otherInputDbs) <- dbF
            isRemix <- isRemixF
            errorOpt <- validateAliceInput(inputRef,
                                           isRemix,
                                           aliceDbOpt,
                                           otherInputDbs)
          } yield errorOpt
        } else Future.successful(accum)
    }

    val f = for {
      (roundDb, aliceDbOpt, otherInputDbs) <- dbF
      isRemix <- isRemixF
      validInputs <- validInputsF
      isMinimal = registerInputs.isMinimal(
        roundParams.getTargetAmount(isRemix, registerInputs.inputs.size))
      isMinimalErr =
        if (isMinimal) None
        else
          Some(new NonMinimalInputsException(
            "Inputs are not minimal, user is attempting to register too many coins"))
      inputsErrorOpt = validInputs.orElse(isMinimalErr)
    } yield (isRemix, aliceDbOpt, inputsErrorOpt, otherInputDbs, roundDb)

    f.flatMap {
      case (isRemix, aliceDbOpt, inputsErrorOpt, otherInputDbs, roundDb) =>
        lazy val changeErrorOpt =
          validateAliceChange(isRemix, registerInputs, otherInputDbs)

        // condense to one error option
        val errorOpt: Option[VortexServerException] =
          inputsErrorOpt.orElse(changeErrorOpt)

        errorOpt match {
          case None =>
            // .get is safe, validInputs will be false
            val aliceDb = aliceDbOpt.get
            val sig =
              km.createBlindSig(registerInputs.blindedOutput, aliceDb.noncePath)

            val inputDbs = registerInputs.inputs.map(
              RegisteredInputDbs.fromInputReference(_, roundId, peerId))

            val updated =
              aliceDb.setOutputValues(isRemix = isRemix,
                                      numInputs = inputDbs.size,
                                      blindedOutput =
                                        registerInputs.blindedOutput,
                                      changeSpkOpt =
                                        registerInputs.changeSpkOpt,
                                      blindOutputSig = sig)

            val action = for {
              _ <- aliceDAO.updateAction(updated)
              _ <- inputsDAO.createAllAction(inputDbs)

              _ = logger.info(s"Alice ${peerId.hex} inputs registered")

              registered <- aliceDAO.numRegisteredForRoundAction(roundId)
              // check if we can to stop waiting for peers
              _ = if (registered >= config.maxPeers) {
                inputsRegisteredP.trySuccess(())
              }
            } yield sig
            safeDatabase.run(action)
          case Some(banError) =>
            banInputs(registerInputs, banError, roundDb.roundId)
        }
    }
  }

  private def banInputs(
      registerInputs: RegisterInputs,
      banError: VortexServerException,
      roundId: DoubleSha256Digest): Future[Nothing] = {
    logger.info(s"Banning ${registerInputs.inputs.size} inputs")
    val bannedUntil =
      TimeUtil.now.plusSeconds(config.badInputsBanDuration.toSeconds)

    val banDbs = registerInputs.inputs
      .map(_.outPoint)
      .map(outpoint =>
        BannedUtxoDb(outpoint,
                     bannedUntil,
                     s"${banError.getMessage} in round ${roundId.hex}"))

    bannedUtxoDAO
      .upsertAll(banDbs)
      .flatMap(_ => Future.failed(banError))
  }

  private[lnvortex] def verifyAndRegisterBob(
      bob: RegisterOutput): Future[Unit] = {
    logger.info("A bob is registering an output")

    val validSpk = bob.output.scriptPubKey.scriptType == config.outputScriptType
    lazy val validSig = bob.verifySig(publicKey, roundId)

    // todo verify address has never been in any tx
    if (!validSig) {
      Future.failed(new InvalidOutputSignatureException(
        s"Bob attempted to register an output with an invalid sig ${bob.sig.hex}"))
    } else if (!validSpk) {
      Future.failed(new InvalidTargetOutputScriptPubKeyException(
        s"Bob attempted to register an output with an invalid script pub key ${bob.output.scriptPubKey.scriptType}"))
    } else {
      val db = RegisteredOutputDb(bob.output, bob.sig, roundId)
      val action = for {
        round <- currentRoundAction()
        _ <- {
          if (round.status != RegisterOutputs)
            DBIO.failed(
              new IllegalStateException(
                s"Round is in invalid state ${round.status}"))
          else if (round.amount != bob.output.value)
            DBIO.failed(new InvalidTargetOutputAmountException(
              s"Output given is incorrect amount, got ${bob.output.value} expected ${round.amount}"))
          else DBIO.successful(())
        }

        aliceDbs <- aliceDAO.findByRoundIdAction(round.roundId)
        inputDbs <- inputsDAO.findByRoundIdAction(round.roundId)
        spks = inputDbs.map(_.output.scriptPubKey) ++ aliceDbs.flatMap(
          _.changeSpkOpt)
        _ <-
          if (spks.contains(bob.output.scriptPubKey)) {
            val bobAddr = BitcoinAddress.fromScriptPubKey(
              bob.output.scriptPubKey,
              config.network)
            DBIO.failed(
              new InvalidTargetOutputScriptPubKeyException(
                s"$bobAddr already registered as an input"))
          } else DBIO.successful(())

        outputDbs <- outputsDAO.findByRoundIdAction(round.roundId)

        _ <-
          if (
            outputDbs.exists(_.output.scriptPubKey == bob.output.scriptPubKey)
          ) {
            val bobAddr = BitcoinAddress.fromScriptPubKey(
              bob.output.scriptPubKey,
              config.network)
            DBIO.failed(
              new InvalidTargetOutputScriptPubKeyException(
                s"$bobAddr already registered as an output"))
          } else DBIO.successful(())

        _ <- outputsDAO.createAction(db)

        registeredAlices <- aliceDAO.numRegisteredForRoundAction(roundId)
        outputs <- outputsDAO.findByRoundIdAction(roundId)
      } yield {
        if (outputs.size >= registeredAlices) {
          outputsRegisteredP.trySuccess(())
        }
        ()
      }

      safeDatabase.run(action)
    }
  }

  private[coordinator] def constructUnsignedPSBT(
      feeAddr: BitcoinAddress): Future[PSBT] = {
    logger.info(s"Constructing unsigned PSBT")
    bitcoind.getBlockCount.flatMap { height =>
      val dbsAction = for {
        aliceDbs <- aliceDAO.findRegisteredForRoundAction(roundId)
        inputDbs <- inputsDAO.findByRoundIdAction(roundId)
        outputDbs <- outputsDAO.findByRoundIdAction(roundId)
        roundDb <- currentRoundAction()
      } yield (aliceDbs, inputDbs, outputDbs, roundDb)

      val action = dbsAction.flatMap {
        case (aliceDbs, inputDbs, outputDbs, roundDb) =>
          val txBuilder = RawTxBuilder()
            .setFinalizer(FilterDustFinalizer.andThen(ShuffleFinalizer))
            .setLockTime(UInt32(height + 1))

          val inputAmountByPeerId = inputDbs
            .groupBy(_.peerId)
            .map { case (peerId, dbs) =>
              val amt = dbs.map(_.output.value).sum
              (peerId, amt)
            }

          val numRemixes = aliceDbs.count(_.isRemix)
          val numNewEntrants = aliceDbs.count(!_.isRemix)

          if (numRemixes < config.minRemixPeers) {
            throw new RuntimeException("Not enough remixes")
          } else if (numNewEntrants < config.minNewPeers) {
            throw new RuntimeException("Not enough new entrants")
          }

          val changeOutputResults = aliceDbs.map { db =>
            FeeCalculator.calculateChangeOutput(
              roundParams = roundParams,
              isRemix = db.isRemix,
              numInputs = db.numInputs,
              numNewEntrants = numNewEntrants,
              numRemixes = numRemixes,
              inputAmount = inputAmountByPeerId(db.peerId),
              changeSpkOpt = db.changeSpkOpt
            )
          }

          val (changeOutputs, excess) = changeOutputResults.foldLeft(
            (Vector.empty[TransactionOutput], CurrencyUnits.zero)) {
            case ((outputs, amt), either) =>
              either match {
                case Left(extraFee)      => (outputs, amt + extraFee)
                case Right(changeOutput) => (outputs :+ changeOutput, amt)
              }
          }

          // add inputs
          logger.trace("Adding inputs to transaction")
          txBuilder ++= inputDbs.map(_.transactionInput)

          val usedExcess = if (excess < Satoshis.zero) Satoshis.zero else excess
          // add outputs
          val coordinatorFee =
            usedExcess + (Satoshis(numNewEntrants) * config.coordinatorFee)
          val coordinatorFeeOutput =
            TransactionOutput(coordinatorFee, feeAddr.scriptPubKey)
          val targetOutputs = outputDbs.map(_.output)
          val outputsToAdd =
            targetOutputs ++ changeOutputs :+ coordinatorFeeOutput

          logger.trace("Adding outputs to transaction")
          txBuilder ++= outputsToAdd

          logger.trace("Constructing transaction")
          val transaction = txBuilder.buildTx()

          val outPoints = transaction.inputs.map(_.previousOutput)

          logger.trace("Constructing PSBT")
          val unsigned = PSBT.fromUnsignedTx(transaction)

          logger.debug("Adding utxos to PSBT")
          val psbt = outPoints.zipWithIndex.foldLeft(unsigned) {
            case (psbt, (outPoint, idx)) =>
              val prevOut = inputDbs.find(_.outPoint == outPoint).get.output
              psbt.addWitnessUTXOToInput(prevOut, idx)
          }

          logger.debug("Updating round status and adding psbt")
          val updatedRound =
            roundDb.copy(psbtOpt = Some(psbt), status = SigningPhase)
          val updatedInputs = inputDbs.map { db =>
            val index = outPoints.indexOf(db.outPoint)
            db.copy(indexOpt = Some(index))
          }

          logger.debug("Creating signedPeersMap")
          inputDbs.map(_.peerId).distinct.foreach { peerId =>
            signedPeersMap.put(peerId, false)
          }

          for {
            _ <- inputsDAO.updateAllAction(updatedInputs)
            _ <- roundDAO.updateAction(updatedRound)
          } yield psbt
      }

      safeDatabase.run(action)
    }
  }

  private[lnvortex] def registerPSBTSignatures(
      peerId: Sha256Digest,
      psbt: PSBT): Future[Unit] = {
    logger.info(s"${peerId.hex} is registering PSBT sigs")

    val action = for {
      roundDb <- currentRoundAction()
      aliceDbOpt <- aliceDAO.findByPrimaryKeyAction(peerId)
      inputs <- inputsDAO.findByPeerIdAction(peerId, roundId)
    } yield (roundDb, aliceDbOpt, inputs)

    safeDatabase.run(action).flatMap {
      case (_, None, _) =>
        Future.failed(
          new RuntimeException(s"No alice found with id ${peerId.hex}"))
      case (roundDb, Some(aliceDb), inputs) =>
        if (roundDb.status != SigningPhase)
          throw new IllegalStateException(
            s"In invalid state ${roundDb.status} should be in state SigningPhase")

        roundDb.psbtOpt match {
          case Some(unsignedPsbt) =>
            val correctNumInputs = inputs.size == aliceDb.numInputs
            val sameTx = unsignedPsbt.transaction == psbt.transaction
            logger.debug("Verifying psbt signatures")
            lazy val validSigs =
              Try(
                inputs
                  .map(_.indexOpt.get)
                  .forall(psbt.verifyFinalizedInput)).getOrElse(false)

            if (correctNumInputs && sameTx && validSigs) {
              // mark successful
              logger.info(s"${peerId.hex} successfully registered signatures")
              for {
                _ <- aliceDAO.update(aliceDb.markSigned(psbt))
                _ = signedPeersMap.update(peerId, true)
              } yield ()
            } else {
              val bannedUntil =
                TimeUtil.now.plusSeconds(
                  config.invalidSignatureBanDuration.toSeconds)

              val exception: Exception = if (!sameTx) {
                new DifferentTransactionException(
                  s"Received different transaction in psbt from peer, got ${psbt.transaction.hex}, expected ${unsignedPsbt.transaction.hex}")
              } else if (!validSigs) {
                new InvalidPSBTSignaturesException(
                  "Received invalid psbt signature from peer")
              } else if (!correctNumInputs) {
                new IllegalStateException(
                  s"numInputs in AliceDb (${aliceDb.numInputs}) did not match the InputDbs (${inputs.size})")
              } else {
                // this should be impossible
                new RuntimeException("Received invalid signed psbt from peer")
              }

              lazy val dbs = inputs
                .map(_.outPoint)
                .map(outpoint =>
                  BannedUtxoDb(
                    outpoint,
                    bannedUntil,
                    s"${exception.getMessage} round ${roundDb.roundId.hex}"))

              // only ban if the user's fault
              val ban = !validSigs || !sameTx

              if (ban) {
                bannedUtxoDAO
                  .upsertAll(dbs)
                  .flatMap(_ => Future.failed(exception))
              } else Future.failed(exception)
            }
          case None =>
            val exception =
              new IllegalStateException("Round in invalid state, no psbt")
            Future.failed(exception)
        }
    }
  }

  def cancelRound(): Future[Unit] = {
    logger.info(s"Canceling round ${roundId.hex}")
    val action = for {
      roundDb <- currentRoundAction()
      _ <- roundDAO.updateAction(roundDb.copy(status = Canceled))
    } yield ()

    safeDatabase
      .run(action)
      .flatMap(_ => VortexCoordinator.nextRound(this))
      .map(_ => ())
  }

  def reconcileRound(): Future[Vector[RestartRoundMessage]] = {
    logger.info(s"Reconciling round ${roundId.hex}")
    // Cancel other potentials calls of this function
    completedTxP.tryFailure(new RuntimeException("Reconciling round"))

    val action = for {
      round <- currentRoundAction()
      _ = logger.debug("Setting round status to Reconciled")
      updated = round.copy(status = Reconciled)
      _ <- roundDAO.updateAction(updated)

      _ = logger.debug("Finding honest alices alices")
      alices <- aliceDAO.findRegisteredForRoundAction(round.roundId)
      unsignedPeerIds = alices.filterNot(_.signed).map(_.peerId)
      signedPeerIds = alices.filter(_.signed).map(_.peerId)

      // ban inputs that didn't sign
      inputsToBan <- inputsDAO.findByPeerIdsAction(unsignedPeerIds,
                                                   round.roundId)
      bannedUntil = TimeUtil.now.plusSeconds(
        config.invalidSignatureBanDuration.toSeconds)
      banReason = s"Alice never signed in round ${round.roundId.hex}"
      banDbs = inputsToBan.map(db =>
        BannedUtxoDb(db.outPoint, bannedUntil, banReason))
      _ = logger.debug(s"Banning inputs ${banDbs.size} that did not sign")
      _ <- bannedUtxoDAO.upsertAllAction(banDbs)

      // delete previous inputs and outputs so they can be registered again
      _ <- inputsDAO.deleteByRoundIdAction(round.roundId)
      _ <- outputsDAO.deleteByRoundIdAction(round.roundId)
      // delete alices that will be remade so they can register again
      _ <- aliceDAO.deleteByPeerIdsAction(signedPeerIds)
    } yield (signedPeerIds, updated)

    for {
      (signedPeerIds, roundDb) <- safeDatabase.run(action)
      // clone connectionHandlerMap because newRound() will clear it
      oldMap = connectionHandlerMap.clone()
      // restart round with good alices
      nextCoordinator <- VortexCoordinator.nextRound(this, announce = false)

      _ = logger.info("Sending restart round messages to honest alices")
      actions = signedPeerIds.map { id =>
        nextCoordinator
          .getNonceAction(peerId = id, nextCoordinator.roundId)
          .flatMap { db =>
            logger.trace(s"Sending restart round message to ${id.hex}")
            val nonceMsg = NonceMessage(db.nonce)
            val restartMsg =
              RestartRoundMessage(nextCoordinator.roundParams, nonceMsg)
            val connectionHandler = oldMap(id)
            nextCoordinator.connectionHandlerMap.put(id, connectionHandler)

            // recover failures for disconnected peers
            val sendF = connectionHandler
              .offer(TextMessage(Json.toJson(restartMsg).toString))
              .map(_ => ())
              .recover(_ => ())
              .map(_ => restartMsg)

            DBIO.from(sendF)
          }
      }

      restartMsgs <- safeDatabase.run(DBIO.sequence(actions))
      // change state so they can begin registering inputs
      _ <- nextCoordinator.beginInputRegistration()

      // execute callback after new round is created so we can
      // safely read data from the database
      _ <- config.callBacks.executeOnRoundReconciled(roundDb)
    } yield restartMsgs
  }

  // -- Server startup logic --

  override def start(): Unit = {
    if (
      roundStartTime == 0 ||
      roundId == DoubleSha256Digest.empty ||
      currentFeeRate == SatoshisPerVirtualByte.zero
    ) {
      throw new IllegalStateException(
        "Cannot start server with invalid round parameters")
    }

    // handling sending blinded sig to alices
    inputsRegisteredP.future
      .flatMap(_ => beginOutputRegistration())
      .recoverWith { case ex: Throwable =>
        logger.info("Failed to complete input registration: ", ex)
        cancelRound().map(_ => ())
      }

    // handle sending psbt when all outputs registered
    outputsRegisteredP.future
      .flatMap(_ => sendUnsignedPSBT())
      .map(_ => ())
      .recoverWith { ex: Throwable =>
        logger.info("Failed to complete output registration: ", ex)
        // We need to make a new round because we
        // don't know which inputs didn't register
        cancelRound().map(_ => ())
      }

    completedTxP.future
      .flatMap(onCompletedTransaction)
      .recoverWith { _ =>
        reconcileRound().map(_ => ())
      }

    // call these so they is no longer lazy and start running
    beginInputRegistrationCancellable
    feeRateHintsCancellable
    ()
  }

  override def stop(): Unit = {
    // wrap in try because this could fail
    // from invalid params for dummy coordinators
    Try(beginInputRegistrationCancellable.cancel())
    Try(feeRateHintsCancellable.cancel())
    beginOutputRegistrationCancellable.foreach(_.cancel())
    sendPSBTCancellable.foreach(_.cancel())

    ()
  }
}

object VortexCoordinator extends Logging {

  def initialize(bitcoind: BitcoindRpcClient)(implicit
      system: ActorSystem,
      ec: ExecutionContext,
      config: VortexCoordinatorAppConfig): Future[VortexCoordinator] = {
    logger.info("Initializing Vortex Coordinator")
    logger.debug("Loading bitcoind wallet")

    bitcoind
      .loadWallet("vortex")
      .map(_ => logger.debug("Loaded bitcoind wallet"))
      .recover(_ => ())
      .flatMap { _ =>
        val km = new CoordinatorKeyManager()
        val dummyOld =
          new VortexCoordinator(km = km,
                                bitcoind = bitcoind,
                                roundId = DoubleSha256Digest.empty,
                                currentFeeRate = SatoshisPerVirtualByte.zero,
                                roundStartTime = 0)

        nextRound(dummyOld)
      }
  }

  private def nextRound(old: VortexCoordinator, announce: Boolean = true)(
      implicit
      system: ActorSystem,
      ec: ExecutionContext): Future[VortexCoordinator] = {
    if (!old.nextCoordinatorP.isCompleted) {
      implicit val config: VortexCoordinatorAppConfig = old.config

      val feeRateF = config.fetchFeeRate()
      old.stop()
      // generate new round id
      val roundId = DoubleSha256Digest(CryptoUtil.randomBytes(32))
      logger.info(s"Creating new Round! ${roundId.hex}")

      val roundStartTime =
        TimeUtil.currentEpochSecond + config.roundInterval.toSeconds

      // disconnect peers so they all get new ids
      old.disconnectPeers()
      // clear some memory
      old.connectionHandlerMap.clear()
      old.signedPeersMap.clear()

      val roundDb = RoundDbs.newRound(
        roundId = roundId,
        roundTime = Instant.ofEpochSecond(roundStartTime),
        coordinatorFee = config.coordinatorFee,
        amount = config.roundAmount
      )

      for {
        _ <- old.roundDAO.create(roundDb)
        feeRate <- feeRateF

        newCoordinator = new VortexCoordinator(old.km,
                                               old.bitcoind,
                                               roundId,
                                               feeRate,
                                               roundStartTime)

        _ = {
          newCoordinator.roundSubscribers ++= old.roundSubscribers
          newCoordinator.start()

          old.nextCoordinatorP.trySuccess(newCoordinator)
        }

        // announce to peers
        _ <-
          if (announce) {
            logger.info("Announcing round to peers")
            val offerFs = old.roundSubscribers.map { queue =>
              queue
                .offer(
                  TextMessage(Json.toJson(newCoordinator.roundParams).toString))
                .map(_ => ())
                .recover { _ =>
                  old.roundSubscribers -= queue
                  ()
                }
            }

            Future.sequence(offerFs)
          } else {
            Future.unit
          }
      } yield newCoordinator
    } else {
      Future.failed(
        new IllegalStateException("New round has already been created."))
    }
  }
}
