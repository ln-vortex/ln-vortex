package com.lnvortex.server.coordinator

import akka.actor._
import com.lnvortex.core.RoundStatus._
import com.lnvortex.core._
import com.lnvortex.server.VortexServerException._
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.internal._
import com.lnvortex.server.models._
import com.lnvortex.server.networking.ServerConnectionHandler.CloseConnection
import com.lnvortex.server.networking.VortexServer
import grizzled.slf4j.Logging
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddressType
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util._
import org.bitcoins.core.wallet.builder._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.feeprovider.MempoolSpaceProvider
import org.bitcoins.feeprovider.MempoolSpaceTarget.FastestFeeTarget
import org.bitcoins.rpc.client.common.BitcoindRpcClient

import java.net.InetSocketAddress
import java.time.Instant
import scala.collection.mutable
import scala.concurrent._
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

  final val version = UInt16.zero

  private[server] val bannedUtxoDAO = BannedUtxoDAO()
  private[server] val aliceDAO = AliceDAO()
  private[server] val inputsDAO = RegisteredInputDAO()
  private[server] val outputsDAO = RegisteredOutputDAO()
  private[server] val roundDAO = RoundDAO()

  private[this] val km = new CoordinatorKeyManager()

  val publicKey: SchnorrPublicKey = km.publicKey

  private val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, config.network, None)

  private var feeRate: SatoshisPerVirtualByte =
    SatoshisPerVirtualByte.fromLong(2)

  private var currentRoundId: DoubleSha256Digest = genRoundId()

  private def genRoundId(): DoubleSha256Digest =
    CryptoUtil.doubleSHA256(ECPrivateKey.freshPrivateKey.bytes)

  def getCurrentRoundId: DoubleSha256Digest = currentRoundId

  private def roundAddressLabel: String = s"Vortex Round: ${currentRoundId.hex}"

  private[coordinator] def inputFee: CurrencyUnit =
    feeRate * 149 // p2wpkh input size
  private[coordinator] def outputFee: CurrencyUnit =
    feeRate * 43 // p2wsh output size

  private var beginInputRegistrationCancellable: Option[Cancellable] =
    None

  private var beginOutputRegistrationCancellable: Option[Cancellable] =
    None

  // On startup consider a round just happened so
  // next round occurs at the interval time
  private var lastRoundTime: Long = TimeUtil.currentEpochSecond

  private def roundStartTime: Long = {
    lastRoundTime + config.mixInterval.toSeconds
  }

  def mixDetails: MixDetails =
    MixDetails(
      version = version,
      roundId = currentRoundId,
      amount = config.mixAmount,
      mixFee = config.mixFee,
      publicKey = publicKey,
      time = UInt64(roundStartTime)
    )

  private var inputRegStartTime = roundStartTime

  def getInputRegStartTime: Long = inputRegStartTime

  private val connectionHandlerMap: mutable.Map[Sha256Digest, ActorRef] =
    mutable.Map.empty

  private var inputsRegisteredP: Promise[Unit] = Promise[Unit]()
  private var outputsRegisteredP: Promise[Unit] = Promise[Unit]()

  private val signedPMap: mutable.Map[Sha256Digest, Promise[PSBT]] =
    mutable.Map.empty

  private var completedTxP: Promise[Transaction] = Promise[Transaction]()

  def newRound(disconnect: Boolean = true): Future[RoundDb] = {
    // generate new round id
    currentRoundId = genRoundId()

    logger.info(s"Creating new Round! ${currentRoundId.hex}")
    val feeRateF = updateFeeRate()

    // reset promises
    inputsRegisteredP = Promise[Unit]()
    outputsRegisteredP = Promise[Unit]()
    completedTxP = Promise[Transaction]()

    // disconnect peers so they all get new ids
    if (disconnect) {
      disconnectPeers()
    }
    // clear maps
    connectionHandlerMap.clear()
    signedPMap.clear()

    lastRoundTime = TimeUtil.currentEpochSecond
    inputRegStartTime = roundStartTime
    for {
      feeRate <- feeRateF
      roundDb = RoundDbs.newRound(
        roundId = currentRoundId,
        roundTime = Instant.ofEpochSecond(inputRegStartTime),
        feeRate = feeRate,
        mixFee = config.mixFee,
        inputFee = inputFee,
        outputFee = outputFee,
        amount = config.mixAmount
      )
      created <- roundDAO.create(roundDb)
    } yield {
      // switch to input registration at correct time
      beginInputRegistrationCancellable = Some(
        system.scheduler.scheduleOnce(config.mixInterval) {
          beginInputRegistration()
          ()
        })

      // handling sending blinded sig to alices
      inputsRegisteredP.future.flatMap(_ => beginOutputRegistration())

      // handle sending psbt when all outputs registered
      outputsRegisteredP.future.flatMap(_ => sendUnsignedPSBT())

      completedTxP.future
        .flatMap(onCompletedTransaction)
        .recoverWith(_ => reconcileRound())

      // return round
      created
    }
  }

  private def disconnectPeers(): Unit = {
    if (connectionHandlerMap.values.nonEmpty) {
      logger.info("Disconnecting peers")
      connectionHandlerMap.values.foreach(_ ! CloseConnection)
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

  private[server] def beginInputRegistration(): Future[Unit] = {
    logger.info("Starting input registration")

    val feeRateF = updateFeeRate()

    beginInputRegistrationCancellable.foreach(_.cancel())
    beginInputRegistrationCancellable = None
    inputRegStartTime = TimeUtil.currentEpochSecond

    for {
      roundDb <- currentRound()
      feeRate <- feeRateF
      updated = roundDb.copy(status = RegisterAlices, feeRate = feeRate)
      _ <- roundDAO.update(updated)
    } yield {
      beginOutputRegistrationCancellable = Some(
        system.scheduler.scheduleOnce(config.inputRegistrationTime) {
          inputsRegisteredP.success(())
          ()
        }
      )
      connectionHandlerMap.values.foreach(
        _ ! AskInputs(currentRoundId, inputFee, outputFee))
    }
  }

  private[server] def beginOutputRegistration(): Future[Unit] = {
    logger.info("Starting output registration")

    beginOutputRegistrationCancellable.foreach(_.cancel())
    beginOutputRegistrationCancellable = None

    for {
      roundDb <- currentRound()
      updated = roundDb.copy(status = RegisterOutputs)
      _ <- roundDAO.update(updated)

      peerSigMap <- aliceDAO.getPeerIdSigMap(currentRoundId)
    } yield {
      system.scheduler.scheduleOnce(config.outputRegistrationTime) {
        outputsRegisteredP.success(())
        ()
      }

      logger.info(s"Sending blinded sigs to ${peerSigMap.size} peers")
      peerSigMap.foreach { case (peerId, sig) =>
        connectionHandlerMap(peerId) ! BlindedSig(sig)
      }
    }
  }

  private[server] def sendUnsignedPSBT(): Future[Unit] = {
    logger.info("Sending unsigned PSBT to peers")
    for {
      addr <- bitcoind.getNewAddress(roundAddressLabel, AddressType.Bech32)
      psbt <- constructUnsignedPSBT(addr)
    } yield connectionHandlerMap.values.foreach(_ ! UnsignedPsbtMessage(psbt))
  }

  private def onCompletedTransaction(tx: Transaction): Future[Unit] = {
    for {
      _ <- bitcoind.sendRawTransaction(tx)
      _ = logger.info(s"Broadcast transaction ${tx.txIdBE.hex}")

      addrs <- bitcoind.listReceivedByAddress(confirmations = 0,
                                              includeEmpty = false,
                                              includeWatchOnly = true,
                                              walletNameOpt = None)
      addrOpt = addrs.find(_.label == roundAddressLabel)
      profitOpt = addrOpt.map(_.amount)
      profit = profitOpt.getOrElse(Satoshis.zero)

      roundDb <- currentRound()
      updatedRoundDb = roundDb.completeRound(tx, profit)

      _ <- roundDAO.update(updatedRoundDb)
      _ <- newRound()
    } yield ()
  }

  def getNonce(
      peerId: Sha256Digest,
      connectionHandler: ActorRef,
      askNonce: AskNonce): Future[AliceDb] = {
    logger.info(s"Alice ${peerId.hex} asked for a nonce")
    require(askNonce.roundId == currentRoundId,
            "Alice asked for nonce of a different roundId")
    aliceDAO.read(peerId).flatMap {
      case Some(alice) =>
        Future.successful(alice)
      case None =>
        val (nonce, path) = km.nextNonce()

        val aliceDb = AliceDbs.newAlice(peerId, currentRoundId, path, nonce)

        connectionHandlerMap.put(peerId, connectionHandler)

        aliceDAO.create(aliceDb).flatMap { db =>
          if (connectionHandlerMap.values.size >= config.maxPeers) {
            beginInputRegistration().map(_ => db)
          } else Future.successful(db)
        }
    }
  }

  def cancelRegistration(
      nonce: SchnorrNonce,
      roundId: DoubleSha256Digest): Future[Unit] = {
    require(roundId == currentRoundId,
            "Attempted to cancel a previous registration")
    for {
      aliceDbOpt <- aliceDAO.findByNonce(nonce)
      aliceDb = aliceDbOpt match {
        case Some(db) => db
        case None =>
          throw new IllegalArgumentException(
            s"No alice found with nonce $nonce")
      }
      updated = aliceDb.unregister()
      _ <- aliceDAO.update(updated)
      _ <- inputsDAO.deleteByPeerId(updated.peerId, updated.roundId)
      _ = signedPMap.remove(updated.peerId)
    } yield ()
  }

  private[server] def registerAlice(
      peerId: Sha256Digest,
      registerInputs: RegisterInputs): Future[FieldElement] = {
    logger.info(s"Alice ${peerId.hex} is registering inputs")
    logger.debug(s"Alice's ${peerId.hex} inputs $registerInputs")

    val roundDbF = currentRound().map { roundDb =>
      if (roundDb.status != RegisterAlices)
        throw new IllegalStateException(
          s"Round in incorrect state ${roundDb.status}")
      roundDb
    }

    val aliceDbF = aliceDAO.read(peerId)

    val otherInputDbsF =
      roundDbF.flatMap(db => inputsDAO.findByRoundId(db.roundId))

    val validInputsF = FutureUtil.foldLeftAsync(true, registerInputs.inputs) {
      case (accum, inputRef) =>
        if (accum) {
          for {
            isRemix <- roundDAO.hasTxId(inputRef.outPoint.txIdBE)

            validRemix =
              if (isRemix) {
                // if we are remixing, can only have one input
                val singleInput = registerInputs.inputs.size == 1
                // make sure it wasn't a change output
                val isMixUtxo = inputRef.output.value == config.remixAmount
                val noChange = registerInputs.changeSpkOpt.isEmpty

                singleInput && isMixUtxo && noChange
              } else true

            res <-
              if (validRemix)
                validateAliceInput(inputRef, isRemix, aliceDbF, otherInputDbsF)
              else Future.successful(false)
          } yield res
        } else Future.successful(false)
    }

    val f = for {
      validInputs <- validInputsF
      otherInputDbs <- otherInputDbsF
      roundDb <- roundDbF
    } yield (validInputs, otherInputDbs, roundDb)

    f.flatMap { case (validInputs, otherInputDbs, roundDb) =>
      lazy val changeErrorOpt =
        validateAliceChange(roundDb, registerInputs, otherInputDbs)

      if (validInputs && changeErrorOpt.isEmpty) {
        // .get is safe, validInputs will be false
        aliceDbF.map(_.get).flatMap { aliceDb =>
          val sig =
            km.createBlindSig(registerInputs.blindedOutput, aliceDb.noncePath)

          val inputDbs = registerInputs.inputs.map(
            RegisteredInputDbs.fromInputReference(_, currentRoundId, peerId))

          val updated =
            aliceDb.setOutputValues(numInputs = inputDbs.size,
                                    blindedOutput =
                                      registerInputs.blindedOutput,
                                    changeSpkOpt = registerInputs.changeSpkOpt,
                                    blindOutputSig = sig)

          for {
            _ <- aliceDAO.update(updated)
            _ <- inputsDAO.createAll(inputDbs)

            _ = logger.info(s"Alice ${peerId.hex} inputs registered")

            registered <- aliceDAO.numRegisteredForRound(currentRoundId)
            // check if we can to stop waiting for peers
            _ = if (registered >= config.maxPeers) {
              inputsRegisteredP.success(())
            }
          } yield sig
        }
      } else {
        val bannedUntil = TimeUtil.now.plusSeconds(3600) // 1 hour

        val exception: Exception = if (!validInputs) {
          new InvalidInputsException("Alice gave invalid inputs")
        } else {
          changeErrorOpt.getOrElse(
            new IllegalArgumentException(
              "Alice registered with invalid inputs"))
        }

        val banDbs = registerInputs.inputs
          .map(_.outPoint)
          .map(outpoint =>
            BannedUtxoDb(
              outpoint,
              bannedUntil,
              s"${exception.getMessage} in round ${roundDb.roundId.hex}"))

        bannedUtxoDAO
          .upsertAll(banDbs)
          .flatMap(_ => Future.failed(exception))
      }
    }
  }

  private[server] def verifyAndRegisterBob(
      bob: RegisterMixOutput): Future[Unit] = {
    logger.info("A bob is registering an output")

    val validSpk = bob.output.scriptPubKey.scriptType == config.mixScriptType
    lazy val validSig = bob.verifySig(publicKey, currentRoundId)

    // todo verify address has never been in any tx
    if (validSpk && validSig) {
      val db = RegisteredOutputDb(bob.output, bob.sig, currentRoundId)
      for {
        round <- currentRound()
        _ = {
          if (round.status != RegisterOutputs)
            throw new IllegalStateException(
              s"Round is in invalid state ${round.status}")
          if (round.amount != bob.output.value)
            throw new InvalidMixOutputAmountException(
              s"Output given is incorrect amount, got ${bob.output.value} expected ${round.amount}")
        }

        aliceDbs <- aliceDAO.findByRoundId(round.roundId)
        inputDbs <- inputsDAO.findByRoundId(round.roundId)
        spks = inputDbs.map(_.output.scriptPubKey) ++ aliceDbs.flatMap(
          _.changeSpkOpt)
        bobAddr = BitcoinAddress.fromScriptPubKey(bob.output.scriptPubKey,
                                                  config.network)
        _ <-
          if (spks.contains(bob.output.scriptPubKey)) {
            Future.failed(
              new InvalidMixOutputScriptPubKeyException(
                s"$bobAddr already registered as an input"))
          } else Future.unit

        _ <- outputsDAO.create(db)

        registeredAlices <- aliceDAO.numRegisteredForRound(currentRoundId)
        outputs <- outputsDAO.findByRoundId(currentRoundId)
      } yield {
        if (outputs.size >= registeredAlices) {
          outputsRegisteredP.success(())
        }
        ()
      }
    } else { // error
      val exception: Exception = if (!validSig) {
        new InvalidOutputSignatureException(
          s"Bob attempted to register an output with an invalid sig ${bob.sig.hex}")
      } else if (!validSpk) {
        new InvalidMixOutputScriptPubKeyException(
          s"Bob attempted to register an output with an invalid script pub key ${bob.output.scriptPubKey}")
      } else {
        // this should be impossible
        new IllegalArgumentException(s"Received invalid output ${bob.output}")
      }

      Future.failed(exception)
    }
  }

  private[server] def calculateChangeOutput(
      roundDb: RoundDb,
      numInputs: Int,
      inputAmount: CurrencyUnit,
      changeSpkOpt: Option[ScriptPubKey]): Either[
    CurrencyUnit,
    TransactionOutput] = {
    val excess = inputAmount - roundDb.amount - roundDb.mixFee - (Satoshis(
      numInputs) * roundDb.inputFee) - roundDb.outputFee

    changeSpkOpt match {
      case Some(changeSpk) =>
        val dummy = TransactionOutput(Satoshis.zero, changeSpk)
        val changeCost = roundDb.feeRate * dummy.byteSize

        val excessAfterChange = excess - changeCost

        if (excessAfterChange > Policy.dustThreshold)
          Right(TransactionOutput(excessAfterChange, changeSpk))
        else Left(excess)
      case None => Left(excess)
    }
  }

  private[coordinator] def constructUnsignedPSBT(
      mixAddr: BitcoinAddress): Future[PSBT] = {
    val dbsF = for {
      aliceDbs <- aliceDAO.findRegisteredForRound(currentRoundId)
      inputDbs <- inputsDAO.findByRoundId(currentRoundId)
      outputDbs <- outputsDAO.findByRoundId(currentRoundId)
      roundDb <- currentRound()
    } yield (aliceDbs, inputDbs, outputDbs, roundDb)

    dbsF.flatMap { case (aliceDbs, inputDbs, outputDbs, roundDb) =>
      val txBuilder = RawTxBuilder().setFinalizer(
        FilterDustFinalizer.andThen(ShuffleFinalizer))

      val inputAmountByPeerId = inputDbs
        .groupBy(_.peerId)
        .map { case (peerId, dbs) =>
          val amt = dbs.map(_.output.value).sum
          (peerId, amt)
        }

      val changeOutputResults = aliceDbs.map { db =>
        calculateChangeOutput(roundDb = roundDb,
                              numInputs = db.numInputs,
                              inputAmount = inputAmountByPeerId(db.peerId),
                              changeSpkOpt = db.changeSpkOpt)
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

      // add outputs
      val mixFee = excess + (Satoshis(aliceDbs.size) * config.mixFee)
      val mixFeeOutput = TransactionOutput(mixFee, mixAddr.scriptPubKey)
      val mixOutputs = outputDbs.map(_.output)
      val outputsToAdd = mixOutputs ++ changeOutputs :+ mixFeeOutput

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
        _ <- inputsDAO.updateAll(updatedInputs)
        _ <- roundDAO.update(updatedRound)
      } yield psbt
    }
  }

  private[server] def registerPSBTSignatures(
      peerId: Sha256Digest,
      psbt: PSBT): Future[Transaction] = {
    logger.info(s"${peerId.hex} is registering PSBT sigs")

    val dbsF = for {
      roundDb <- currentRound()
      aliceDbOpt <- aliceDAO.read(peerId)
      inputs <- inputsDAO.findByPeerId(peerId, currentRoundId)
    } yield (roundDb, aliceDbOpt, inputs)

    dbsF.flatMap {
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
                  .forall(psbt.verifyFinalizedInput)).toOption.getOrElse(false)

            if (correctNumInputs && sameTx && validSigs) {
              // mark successful
              signedPMap(peerId).success(psbt)
              val markSignedF = aliceDAO.update(aliceDb.markSigned())

              val signedFs = signedPMap.values.map(_.future)

              val signedT = Try {
                val psbts =
                  Await.result(Future.sequence(signedFs), config.signingTime)

                val head = psbts.head
                val combined = psbts.tail.foldLeft(head)(_.combinePSBT(_))

                combined.extractTransactionAndValidate
              }.flatten

              // make promise complete
              completedTxP.tryComplete(signedT)

              for {
                _ <- markSignedF
                signed <- Future.fromTry(signedT)
              } yield signed
            } else {
              val bannedUntil = TimeUtil.now.plusSeconds(86400) // 1 day

              val exception: Exception = if (!sameTx) {
                new DifferentTransactionException(
                  s"Received different transaction in psbt from peer: ${psbt.transaction.txIdBE.hex}")
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

  def reconcileRound(): Future[Unit] = {
    for {
      // cancel round
      round <- currentRound()
      updated = round.copy(status = Canceled)
      _ <- roundDAO.update(updated)

      // get alices
      alices <- aliceDAO.findRegisteredForRound(round.roundId)
      unsignedPeerIds = alices.filterNot(_.signed).map(_.peerId)
      signedPeerIds = alices.filter(_.signed).map(_.peerId)

      // ban inputs that didn't sign
      inputsToBan <- inputsDAO.findByPeerIds(unsignedPeerIds, round.roundId)
      bannedUntil = TimeUtil.now.plusSeconds(86400) // 1 day
      banReason = s"Alice never signed in round ${round.roundId.hex}"
      banDbs = inputsToBan.map(db =>
        BannedUtxoDb(db.outPoint, bannedUntil, banReason))
      _ <- bannedUtxoDAO.upsertAll(banDbs)

      // delete previous inputs and outputs so they can registered again
      _ <- inputsDAO.deleteByRoundId(round.roundId)
      _ <- outputsDAO.deleteByRoundId(round.roundId)

      // save connectionHandlerMap because newRound() will clear it
      oldMap = connectionHandlerMap
      // restart round with good alices
      newRound <- newRound(disconnect = false)

      // send messages
      _ <- FutureUtil.sequentially(signedPeerIds) { id =>
        val connectionHandler = oldMap(id)
        getNonce(id, oldMap(id), AskNonce(newRound.roundId)).map { db =>
          val nonceMsg = NonceMessage(db.nonce)
          connectionHandler ! RestartRoundMessage(mixDetails, nonceMsg)
        }
      }

      // change state so they can begin registering inputs
      _ <- beginInputRegistration()
    } yield ()
  }

  private def updateFeeRate(): Future[SatoshisPerVirtualByte] = {
    feeProvider.getFeeRate.map { res =>
      feeRate = res
      res
    }
  }

  // -- Server startup logic --

  private val hostAddressP: Promise[InetSocketAddress] =
    Promise[InetSocketAddress]()

  private[lnvortex] lazy val serverBindF: Future[
    (InetSocketAddress, ActorRef)] = {
    logger.info(
      s"Binding coordinator to ${config.listenAddress}, with tor hidden service: ${config.torParams.isDefined}")

    val bindF = VortexServer.bind(vortexCoordinator = this,
                                  bindAddress = config.listenAddress,
                                  torParams = config.torParams)

    bindF.map { case (addr, actor) =>
      hostAddressP.success(addr)
      (addr, actor)
    }
  }

  override def start(): Future[Unit] = {
    for {
      _ <- newRound()
      _ <- serverBindF
    } yield ()
  }

  override def stop(): Future[Unit] = {
    beginInputRegistrationCancellable.foreach(_.cancel())
    beginOutputRegistrationCancellable.foreach(_.cancel())
    serverBindF.map { case (_, actorRef) =>
      system.stop(actorRef)
    }
  }

  def getHostAddress: Future[InetSocketAddress] = {
    hostAddressP.future
  }
}
