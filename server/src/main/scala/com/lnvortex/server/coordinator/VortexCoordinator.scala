package com.lnvortex.server.coordinator

import akka.actor._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
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
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util._
import org.bitcoins.core.wallet.builder._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.feeprovider.MempoolSpaceTarget.FastestFeeTarget
import org.bitcoins.feeprovider._
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import play.api.libs.json.Json
import slick.dbio._

import java.net.InetSocketAddress
import java.time.Instant
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util._

case class VortexCoordinator(bitcoind: BitcoindRpcClient)(implicit
    system: ActorSystem,
    val config: VortexCoordinatorAppConfig)
    extends StartStopAsync[Unit]
    with PeerValidation
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

  private[this] lazy val km = new CoordinatorKeyManager()

  lazy val publicKey: SchnorrPublicKey = km.publicKey

  private val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, config.network, None)

  private val feeProviderBackup: BitcoinerLiveFeeRateProvider =
    BitcoinerLiveFeeRateProvider(30, None)

  private var feeRate: SatoshisPerVirtualByte =
    SatoshisPerVirtualByte.fromLong(2)

  private var currentRoundId: DoubleSha256Digest = genRoundId()

  private def genRoundId(): DoubleSha256Digest =
    CryptoUtil.doubleSHA256(ECPrivateKey.freshPrivateKey.bytes)

  def getCurrentRoundId: DoubleSha256Digest = currentRoundId

  private def roundAddressLabel: String = s"Vortex Round: ${currentRoundId.hex}"

  private[coordinator] def inputFee: CurrencyUnit = {
    FeeCalculator.inputFee(feeRate, config.inputScriptType)
  }

  private[coordinator] def outputFee(
      feeRate: SatoshisPerVirtualByte = feeRate,
      numPeersOpt: Option[Int] = Some(config.minPeers)): CurrencyUnit = {
    FeeCalculator.outputFee(feeRate = feeRate,
                            outputScriptType = config.outputScriptType,
                            coordinatorScriptType = config.changeScriptType,
                            numPeersOpt = numPeersOpt)
  }

  private[coordinator] def changeOutputFee: CurrencyUnit = {
    FeeCalculator.changeOutputFee(feeRate, config.changeScriptType)
  }

  private var beginInputRegistrationCancellable: Option[Cancellable] =
    None

  private var beginOutputRegistrationCancellable: Option[Cancellable] =
    None

  private var sendPSBTCancellable: Option[Cancellable] =
    None

  // On startup consider a round just happened so
  // next round occurs at the interval time
  private var lastRoundTime: Long = TimeUtil.currentEpochSecond

  private def roundStartTime: Long = {
    lastRoundTime + config.roundInterval.toSeconds
  }

  def roundParams: RoundParameters =
    RoundParameters(
      version = version,
      roundId = currentRoundId,
      amount = config.roundAmount,
      coordinatorFee = config.coordinatorFee,
      publicKey = publicKey,
      time = roundStartTime,
      maxPeers = config.maxPeers,
      inputType = config.inputScriptType,
      outputType = config.outputScriptType,
      changeType = config.changeScriptType,
      status = config.statusString
    )

  private[lnvortex] val roundSubscribers =
    mutable.ListBuffer[SourceQueueWithComplete[Message]]()

  private[lnvortex] val connectionHandlerMap: mutable.Map[
    Sha256Digest,
    SourceQueueWithComplete[Message]] =
    mutable.Map.empty

  private var inputsRegisteredP: Promise[Unit] = Promise[Unit]()
  private var outputsRegisteredP: Promise[Unit] = Promise[Unit]()

  private val signedPMap: mutable.Map[Sha256Digest, Promise[PSBT]] =
    mutable.Map.empty

  private var completedTxP: Promise[Transaction] = Promise[Transaction]()

  def newRound(): Future[RoundDb] = {
    val feeRateF = updateFeeRate()
    // generate new round id
    currentRoundId = genRoundId()

    logger.info(s"Creating new Round! ${currentRoundId.hex}")

    beginInputRegistrationCancellable = None
    beginOutputRegistrationCancellable = None
    sendPSBTCancellable = None

    // reset promises
    inputsRegisteredP = Promise[Unit]()
    outputsRegisteredP = Promise[Unit]()
    completedTxP = Promise[Transaction]()

    // disconnect peers so they all get new ids
    disconnectPeers()
    // clear maps
    connectionHandlerMap.clear()
    signedPMap.clear()

    lastRoundTime = TimeUtil.currentEpochSecond
    for {
      feeRate <- feeRateF
      roundDb = RoundDbs.newRound(
        roundId = currentRoundId,
        roundTime = Instant.ofEpochSecond(roundStartTime),
        feeRate = feeRate,
        coordinatorFee = config.coordinatorFee,
        inputFee = inputFee,
        outputFee = outputFee(),
        changeFee = changeOutputFee,
        amount = config.roundAmount
      )
      created <- roundDAO.create(roundDb)

      offerFs = roundSubscribers.map { queue =>
        queue.offer(TextMessage(Json.toJson(roundParams).toString))
      }
      _ <- Future.sequence(offerFs)
    } yield {
      // switch to input registration at correct time
      beginInputRegistrationCancellable = Some(
        system.scheduler.scheduleOnce(config.roundInterval) {
          beginInputRegistration()
            .recoverWith { _ =>
              reconcileRound(isNewRound = true).map(_ => ())
            }
          ()
        })

      // handling sending blinded sig to alices
      inputsRegisteredP.future
        .flatMap(_ => beginOutputRegistration())
        .recoverWith { case ex: Throwable =>
          logger.info("Failed to complete input registration: ", ex)
          reconcileRound(isNewRound = true).map(_ => ())
        }

      // handle sending psbt when all outputs registered
      outputsRegisteredP.future
        .flatMap(_ => sendUnsignedPSBT())
        .map(_ => ())
        .recoverWith { ex: Throwable =>
          logger.info("Failed to complete output registration: ", ex)
          // We need to make a new round because we
          // don't know which inputs didn't register
          reconcileRound(isNewRound = true).map(_ => ())
        }

      completedTxP.future
        .flatMap(onCompletedTransaction)
        .recoverWith { _ =>
          reconcileRound(isNewRound = false).map(_ => ())
        }

      // return round
      created
    }
  }

  private def disconnectPeers(): Unit = {
    if (connectionHandlerMap.values.nonEmpty) {
      logger.info("Disconnecting peers")
      connectionHandlerMap.values.foreach { peer =>
        peer.complete()
      }
    }
  }

  def currentRound(): Future[RoundDb] = {
    getRound(currentRoundId)
  }

  def getRound(roundId: DoubleSha256Digest): Future[RoundDb] = {
    roundDAO.read(roundId).map {
      case Some(db) => db
      case None =>
        throw new RuntimeException(
          s"Could not find a round db for roundId $currentRoundId")
    }
  }

  def currentRoundAction(): DBIOAction[RoundDb, NoStream, Effect.Read] = {
    getRoundAction(currentRoundId)
  }

  def getRoundAction(roundId: DoubleSha256Digest): DBIOAction[
    RoundDb,
    NoStream,
    Effect.Read] = {
    roundDAO.findByPrimaryKeyAction(roundId).map {
      case Some(db) => db
      case None =>
        throw new RuntimeException(
          s"Could not find a round db for roundId $currentRoundId")
    }
  }

  private[lnvortex] def beginInputRegistration(): Future[AskInputs] = {
    logger.info("Starting input registration")

    beginInputRegistrationCancellable.foreach(_.cancel())
    beginInputRegistrationCancellable = None

    // Wait 3 seconds to help prevent race conditions in clients
    val feeRateF =
      AsyncUtil.nonBlockingSleep(3.seconds).flatMap(_ => updateFeeRate())

    feeRateF.flatMap { feeRate =>
      val action = for {
        roundDb <- currentRoundAction()
        updated = roundDb.copy(status = RegisterAlices, feeRate = feeRate)
        _ <- roundDAO.updateAction(updated)
        alices <- aliceDAO.findByRoundIdAction(roundDb.roundId)
      } yield alices.size

      safeDatabase.run(action).flatMap { numAlices =>
        if (numAlices >= config.minPeers) {
          // Create Output Registration timer
          beginOutputRegistrationCancellable = Some(
            system.scheduler.scheduleOnce(config.inputRegistrationTime) {
              inputsRegisteredP.success(())
              ()
            }
          )

          logger.info("Sending AskInputs to peers")
          val msg =
            AskInputs(currentRoundId, inputFee, outputFee(), changeOutputFee)
          // send messages async
          val parallelism = FutureUtil.getParallelism
          Source(connectionHandlerMap.values.toVector)
            .mapAsync(parallelism = parallelism) { peer =>
              peer.offer(TextMessage(Json.toJson(msg).toString))
            }
            .run()
            .map(_ => msg)
        } else {
          Future.failed(new RuntimeException("Not enough peers registered."))
        }
      }
    }
  }

  private[lnvortex] def beginOutputRegistration(): Future[Unit] = {
    logger.info("Starting output registration")

    beginOutputRegistrationCancellable.foreach(_.cancel())
    beginOutputRegistrationCancellable = None

    val action = for {
      roundDb <- currentRoundAction()
      updated = roundDb.copy(status = RegisterOutputs)
      _ <- roundDAO.updateAction(updated)

      alices <- aliceDAO.findRegisteredForRoundAction(currentRoundId)
    } yield {
      val numRemixes = alices.count(_.isRemix)
      val numNewEntrants = alices.count(!_.isRemix)

      if (numRemixes < config.minRemixPeers) {
        throw new RuntimeException(
          s"Not enough remixes: $numRemixes < ${config.minRemixPeers}")
      } else if (numNewEntrants < config.minNewPeers) {
        throw new RuntimeException(
          s"Not enough new entrants: $numNewEntrants < ${config.minNewPeers}")
      }

      sendPSBTCancellable = Some(
        system.scheduler.scheduleOnce(config.outputRegistrationTime) {
          Try(outputsRegisteredP.success(()))
          ()
        })

      alices
    }

    safeDatabase.run(action).flatMap { alices =>
      logger.info(s"Sending blinded sigs to ${alices.size} peers")
      val peerSigMap = alices.map(t => (t.peerId, t.blindOutputSigOpt.get))
      val sendFs = peerSigMap.map { case (peerId, sig) =>
        val peer = connectionHandlerMap(peerId)
        logger.debug(s"Sending blinded sig to ${peerId.hex}")
        val msg = TextMessage(Json.toJson(BlindedSig(sig)).toString)
        peer.offer(msg)
      }
      Future.sequence(sendFs).map(_ => ())
    }
  }

  private[lnvortex] def sendUnsignedPSBT(): Future[PSBT] = {
    logger.info("Sending unsigned PSBT to peers")

    sendPSBTCancellable.foreach(_.cancel())
    sendPSBTCancellable = None

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

    for {
      addr <- bitcoind.getNewAddress(roundAddressLabel, addressType)
      psbt <- constructUnsignedPSBT(addr)

      msg = TextMessage(Json.toJson(UnsignedPsbtMessage(psbt)).toString)
      sendFs = connectionHandlerMap.values.map(_.offer(msg))
      _ <- Future.sequence(sendFs)
    } yield psbt
  }

  private[lnvortex] def onCompletedTransaction(
      tx: Transaction): Future[Unit] = {
    val f = for {
      _ <- bitcoind.sendRawTransaction(tx)
      _ = logger.info(s"Broadcasted transaction ${tx.txIdBE.hex}")

      addrs <- bitcoind.listReceivedByAddress(confirmations = 0,
                                              includeEmpty = false,
                                              includeWatchOnly = true,
                                              walletNameOpt = None)
      addrOpt = addrs.find(_.label == roundAddressLabel)
      profitOpt = addrOpt.map(_.amount)
      profit = profitOpt.getOrElse(Satoshis.zero)

      action = currentRoundAction().flatMap { roundDb =>
        val updatedRoundDb = roundDb.completeRound(tx, profit)
        roundDAO.updateAction(updatedRoundDb)
      }

      _ <- safeDatabase.run(action)
      _ <- newRound()
    } yield ()

    f.failed.foreach { err =>
      err.printStackTrace()
      logger.error("Error completing round", err)
    }

    f
  }

  private def getNonceAction(
      peerId: Sha256Digest,
      peerRoundId: DoubleSha256Digest): DBIOAction[
    AliceDb,
    NoStream,
    Effect.Read with Effect.Write] = {
    logger.info(s"Alice ${peerId.hex} asked for a nonce")
    require(
      peerRoundId == getCurrentRoundId,
      s"Alice asked for nonce of a different roundId ${peerRoundId.hex} != ${getCurrentRoundId.hex}")

    aliceDAO.findByPrimaryKeyAction(peerId).flatMap {
      case Some(alice) => DBIO.successful(alice)
      case None =>
        for {
          (nonce, path) <- km.nextNonce()

          aliceDb = AliceDbs.newAlice(peerId, currentRoundId, path, nonce)

          db <- aliceDAO.createAction(aliceDb)
        } yield db
    }
  }

  def getNonce(
      peerId: Sha256Digest,
      connectionHandler: SourceQueueWithComplete[Message],
      peerRoundId: DoubleSha256Digest): Future[AliceDb] = {
    safeDatabase.run(getNonceAction(peerId, peerRoundId)).flatMap { db =>
      connectionHandlerMap.put(peerId, connectionHandler)

      // Call this synchronized so that we don't send AskInputs twice
      synchronized {
        if (
          connectionHandlerMap.values.size >= config.maxPeers
          && beginInputRegistrationCancellable.isDefined
        ) {
          beginInputRegistration().map(_ => db)
        } else Future.successful(db)
      }
    }
  }

  def cancelRegistration(
      either: Either[SchnorrNonce, Sha256Digest],
      roundId: DoubleSha256Digest): Future[Unit] = {
    if (roundId == currentRoundId) {
      val action = for {
        aliceDbOpt <- aliceDAO.findByEitherAction(either)
        aliceDb = aliceDbOpt match {
          case Some(db) =>
            require(db.roundId == currentRoundId,
                    s"Wrong roundId ${db.roundId.hex} != ${currentRoundId.hex}")
            db
          case None =>
            throw new IllegalArgumentException(s"No alice found with $either")
        }
        _ = logger.info(s"Alice ${aliceDb.peerId.hex} canceling registration")

        _ <- inputsDAO.deleteByPeerIdAction(aliceDb.peerId, aliceDb.roundId)
        _ <- aliceDAO.deleteAction(aliceDb)
        _ = signedPMap.remove(aliceDb.peerId)
        _ = connectionHandlerMap.remove(aliceDb.peerId)
      } yield ()

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

    val isRemixA = if (registerInputs.inputs.size == 1) {
      val inputRef = registerInputs.inputs.head
      roundDAO.hasTxIdAction(inputRef.outPoint.txIdBE).map { isPrevRound =>
        // make sure it wasn't a change output
        val wasTargetUtxo = inputRef.output.value == config.roundAmount
        val noChange = registerInputs.changeSpkOpt.isEmpty
        isPrevRound && wasTargetUtxo && noChange
      }
    } else DBIO.successful(false)

    val action = for {
      roundDb <- roundDbA
      aliceDb <- aliceDAO.findByPrimaryKeyAction(peerId)
      otherInputDbs <- inputsDAO.findByRoundIdAction(roundDb.roundId)
      isRemix <- isRemixA
    } yield (roundDb, aliceDb, otherInputDbs, isRemix)

    val dbF = safeDatabase.run(action)

    val init: Option[InvalidInputsException] = None
    val validInputsF = FutureUtil.foldLeftAsync(init, registerInputs.inputs) {
      case (accum, inputRef) =>
        if (accum.isEmpty) {
          for {
            (_, aliceDb, otherInputDbs, isRemix) <- dbF
            errorOpt <- validateAliceInput(inputRef,
                                           isRemix,
                                           aliceDb,
                                           otherInputDbs)
          } yield errorOpt
        } else Future.successful(accum)
    }

    val f = for {
      (roundDb, aliceDbOpt, otherInputDbs, isRemix) <- dbF
      validInputs <- validInputsF
      isMinimal = registerInputs.isMinimal(roundParams.getTargetAmount(isRemix))
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
          validateAliceChange(isRemix, roundDb, registerInputs, otherInputDbs)

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
              RegisteredInputDbs.fromInputReference(_, currentRoundId, peerId))

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

              registered <- aliceDAO.numRegisteredForRoundAction(currentRoundId)
              // check if we can to stop waiting for peers
              _ = if (registered >= config.maxPeers) {
                inputsRegisteredP.success(())
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
    lazy val validSig = bob.verifySig(publicKey, currentRoundId)

    // todo verify address has never been in any tx
    if (!validSig) {
      Future.failed(new InvalidOutputSignatureException(
        s"Bob attempted to register an output with an invalid sig ${bob.sig.hex}"))
    } else if (!validSpk) {
      Future.failed(new InvalidTargetOutputScriptPubKeyException(
        s"Bob attempted to register an output with an invalid script pub key ${bob.output.scriptPubKey.scriptType}"))
    } else {
      val db = RegisteredOutputDb(bob.output, bob.sig, currentRoundId)
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
        bobAddr = BitcoinAddress.fromScriptPubKey(bob.output.scriptPubKey,
                                                  config.network)
        _ <-
          if (spks.contains(bob.output.scriptPubKey)) {
            DBIO.failed(
              new InvalidTargetOutputScriptPubKeyException(
                s"$bobAddr already registered as an input"))
          } else DBIO.successful(())

        _ <- outputsDAO.createAction(db)

        registeredAlices <- aliceDAO.numRegisteredForRoundAction(currentRoundId)
        outputs <- outputsDAO.findByRoundIdAction(currentRoundId)
      } yield {
        if (outputs.size >= registeredAlices) {
          outputsRegisteredP.success(())
        }
        ()
      }

      safeDatabase.run(action)
    }
  }

  private[server] def calculateChangeOutput(
      roundDb: RoundDb,
      isRemix: Boolean,
      numInputs: Int,
      numRemixes: Int,
      numNewEntrants: Int,
      inputAmount: CurrencyUnit,
      changeSpkOpt: Option[ScriptPubKey]): Either[
    CurrencyUnit,
    TransactionOutput] = {
    if (isRemix) Left(Satoshis.zero)
    else {
      val updatedOutputFee = outputFee(roundDb.feeRate, Some(numNewEntrants))
      val totalNewEntrantFee =
        Satoshis(numRemixes) * (roundDb.inputFee + outputFee(roundDb.feeRate,
                                                             None))
      val newEntrantFee = totalNewEntrantFee / Satoshis(numNewEntrants)
      val excess =
        inputAmount - roundDb.amount - roundDb.coordinatorFee - (Satoshis(
          numInputs) * roundDb.inputFee) - updatedOutputFee - newEntrantFee

      changeSpkOpt match {
        case Some(changeSpk) =>
          val dummy = TransactionOutput(Satoshis.zero, changeSpk)
          val changeCost = roundDb.feeRate * dummy.byteSize

          val excessAfterChange = excess - changeCost

          if (excessAfterChange >= Policy.dustThreshold)
            Right(TransactionOutput(excessAfterChange, changeSpk))
          else Left(excess)
        case None => Left(excess)
      }
    }
  }

  private[coordinator] def constructUnsignedPSBT(
      feeAddr: BitcoinAddress): Future[PSBT] = {
    bitcoind.getBlockCount.flatMap { height =>
      val dbsAction = for {
        aliceDbs <- aliceDAO.findRegisteredForRoundAction(currentRoundId)
        inputDbs <- inputsDAO.findByRoundIdAction(currentRoundId)
        outputDbs <- outputsDAO.findByRoundIdAction(currentRoundId)
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
            calculateChangeOutput(
              roundDb = roundDb,
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

          txBuilder ++= outputsToAdd

          val transaction = txBuilder.buildTx()

          val outPoints = transaction.inputs.map(_.previousOutput)

          val unsigned = PSBT.fromUnsignedTx(transaction)

          val psbt = outPoints.zipWithIndex.foldLeft(unsigned) {
            case (psbt, (outPoint, idx)) =>
              val prevOut = inputDbs.find(_.outPoint == outPoint).get.output
              psbt.addWitnessUTXOToInput(prevOut, idx)
          }

          val updatedRound =
            roundDb.copy(psbtOpt = Some(psbt), status = SigningPhase)
          val updatedInputs = inputDbs.map { db =>
            val index = outPoints.indexOf(db.outPoint)
            db.copy(indexOpt = Some(index))
          }

          inputDbs.map(_.peerId).distinct.foreach { peerId =>
            signedPMap.put(peerId, Promise[PSBT]())
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
      psbt: PSBT): Future[Transaction] = {
    logger.info(s"${peerId.hex} is registering PSBT sigs")

    val action = for {
      roundDb <- currentRoundAction()
      aliceDbOpt <- aliceDAO.findByPrimaryKeyAction(peerId)
      inputs <- inputsDAO.findByPeerIdAction(peerId, currentRoundId)
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
            lazy val validSigs =
              Try(
                inputs
                  .map(_.indexOpt.get)
                  .forall(psbt.verifyFinalizedInput)).getOrElse(false)

            if (correctNumInputs && sameTx && validSigs) {
              // mark successful
              signedPMap(peerId).success(psbt)
              val markSignedF = aliceDAO.update(aliceDb.markSigned(psbt))

              val signedFs = signedPMap.values.map(_.future)

              val signedT = Try {
                val psbts =
                  Await.result(Future.sequence(signedFs), config.signingTime)

                val combined = psbts.reduce(_ combinePSBT _)
                combined.extractTransactionAndValidate
              }.flatten

              for {
                _ <- markSignedF
                signed <- Future.fromTry(signedT)
                msg = SignedTxMessage(signed)

                // Announce to peers
                _ <- connectionHandlerMap(peerId).offer(
                  TextMessage(Json.toJson(msg).toString))

                // mark promise complete
                _ = completedTxP.tryComplete(signedT)
              } yield signed
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

              signedPMap(peerId).failure(exception)

              if (ban) {
                bannedUtxoDAO
                  .upsertAll(dbs)
                  .flatMap(_ => Future.failed(exception))
              } else Future.failed(exception)
            }
          case None =>
            val exception =
              new IllegalStateException("Round in invalid state, no psbt")
            signedPMap(peerId).failure(exception)
            Future.failed(exception)
        }
    }
  }

  def reconcileRound(
      isNewRound: Boolean): Future[Vector[RestartRoundMessage]] = {
    val action = for {
      // cancel round
      round <- currentRoundAction()
      updated = round.copy(status = Canceled)
      _ <- roundDAO.updateAction(updated)

      signedPeerIds <-
        if (isNewRound) DBIO.successful(Vector.empty)
        else {
          for {
            // get alices
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
            _ <- bannedUtxoDAO.upsertAllAction(banDbs)
          } yield signedPeerIds
        }

      // delete previous inputs and outputs so they can registered again
      _ <- inputsDAO.deleteByRoundIdAction(round.roundId)
      _ <- outputsDAO.deleteByRoundIdAction(round.roundId)
    } yield signedPeerIds

    for {
      signedPeerIds <- safeDatabase.run(action)
      // clone connectionHandlerMap because newRound() will clear it
      oldMap = connectionHandlerMap.clone()
      // restart round with good alices
      newRound <- newRound()

      restartMsgs <-
        if (isNewRound) Future.successful(Vector.empty)
        else {
          // change state so they can begin registering inputs
          beginInputRegistration().flatMap { _ =>
            FutureUtil.sequentially(signedPeerIds) { id =>
              val connectionHandler = oldMap(id)
              getNonce(id, oldMap(id), newRound.roundId).flatMap { db =>
                val nonceMsg = NonceMessage(db.nonce)
                val restartMsg = RestartRoundMessage(roundParams, nonceMsg)

                connectionHandler
                  .offer(TextMessage(Json.toJson(restartMsg).toString))
                  .map(_ => restartMsg)
              }
            }
          }
        }
    } yield restartMsgs
  }

  private def updateFeeRate(): Future[SatoshisPerVirtualByte] = {
    val feeRateF =
      feeProvider.getFeeRate().recoverWith { case _: Throwable =>
        feeProviderBackup.getFeeRate().recover { case _: Throwable =>
          config.network match {
            case MainNet | TestNet3 | SigNet =>
              throw new RuntimeException(
                "Failed to get fee rate from fee providers")
            case RegTest =>
              SatoshisPerVirtualByte.fromLong(1)
          }
        }
      }

    feeRateF.map { res =>
      feeRate = res
      res
    }
  }

  // -- Server startup logic --

  private val hostAddressP: Promise[InetSocketAddress] =
    Promise[InetSocketAddress]()

  override def start(): Future[Unit] = {
    for {
      _ <- bitcoind
        .loadWallet("vortex")
        .recover(_ => ())
      _ <- newRound()
    } yield ()
  }

  override def stop(): Future[Unit] = {
    beginInputRegistrationCancellable.foreach(_.cancel())
    beginOutputRegistrationCancellable.foreach(_.cancel())
    sendPSBTCancellable.foreach(_.cancel())
    Future.unit
  }

  def getHostAddress: Future[InetSocketAddress] = {
    hostAddressP.future
  }
}
