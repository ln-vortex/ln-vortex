package com.lnvortex.server.coordinator

import akka.actor._
import com.lnvortex.core.RoundStatus._
import com.lnvortex.core._
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.models._
import com.lnvortex.server.networking.ServerConnectionHandler.CloseConnection
import com.lnvortex.server.networking.VortexServer
import grizzled.slf4j.Logging
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddressType
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.core.util.{StartStopAsync, TimeUtil}
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
    with Logging {
  import system.dispatcher

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
    SatoshisPerVirtualByte.fromLong(0)

  private var currentRoundId: DoubleSha256Digest = genRoundId

  private def genRoundId: DoubleSha256Digest =
    CryptoUtil.doubleSHA256(ECPrivateKey.freshPrivateKey.bytes)

  def getCurrentRoundId: DoubleSha256Digest = currentRoundId

  private[coordinator] def inputFee: CurrencyUnit =
    feeRate * 149 // p2wpkh input size
  private[coordinator] def outputFee: CurrencyUnit =
    feeRate * 43 // p2wsh output size

  private var beginInputRegistrationCancellable: Option[Cancellable] =
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
      inputFee = inputFee,
      outputFee = outputFee,
      publicKey = km.publicKey,
      time = UInt64(roundStartTime)
    )

  private var inputRegStartTime = roundStartTime

  def getInputRegStartTime: Long = inputRegStartTime

  private val connectionHandlerMap: mutable.Map[Sha256Digest, ActorRef] =
    mutable.Map.empty

  private var outputsRegisteredP: Promise[Unit] = Promise[Unit]()

  private val signedPMap: mutable.Map[Sha256Digest, Promise[PSBT]] =
    mutable.Map.empty

  private var completedTxP: Promise[(Transaction, Int)] =
    Promise[(Transaction, Int)]()

  def newRound(): Future[RoundDb] = {
    // generate new round id
    currentRoundId = genRoundId

    logger.info(s"Creating new Round! ${currentRoundId.hex}")
    val feeRateF = updateFeeRate()

    // reset promises
    outputsRegisteredP = Promise[Unit]()
    completedTxP = Promise[(Transaction, Int)]()

    // disconnect peers so they all get new ids
    disconnectPeers()
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

      // todo make timeout for alices not registering
      // handle sending psbt when all outputs registered
      outputsRegisteredP.future.flatMap(_ => sendUnsignedPSBT())

      // todo handle if alices don't sign
      completedTxP.future.flatMap { case (tx, numAlices) =>
        // handle making new round
        for {
          _ <- bitcoind.sendRawTransaction(tx)
          _ = logger.info(s"Broadcast transaction ${tx.txIdBE.hex}")

          roundDb <- currentRound()
          profit = Satoshis(numAlices) * roundDb.mixFee
          updatedRoundDb = roundDb.copy(status = RoundStatus.Signed,
                                        transactionOpt = Some(tx),
                                        profitOpt = Some(profit))

          _ <- roundDAO.update(updatedRoundDb)
          _ <- newRound()
        } yield ()
      }

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
    beginInputRegistrationCancellable.foreach(_.cancel())
    beginInputRegistrationCancellable = None
    inputRegStartTime = TimeUtil.currentEpochSecond
    for {
      roundDb <- roundDAO.read(currentRoundId).map(_.get)
      updated = roundDb.copy(status = RegisterAlices)
      _ <- roundDAO.update(updated)
    } yield ()
  }

  private[server] def beginOutputRegistration(): Future[Unit] = {
    logger.info("Starting output registration")
    for {
      roundDb <- roundDAO.read(currentRoundId).map(_.get)
      updated = roundDb.copy(status = RegisterOutputs)
      _ <- roundDAO.update(updated)
    } yield ()
  }

  private[server] def sendUnsignedPSBT(): Future[Unit] = {
    logger.info("Sending unsigned PSBT to peers")
    for {
      addr <- bitcoind.getNewAddress(AddressType.Bech32)
      psbt <- constructUnsignedPSBT(addr)
    } yield connectionHandlerMap.values.foreach(_ ! UnsignedPsbtMessage(psbt))
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

        aliceDAO.create(aliceDb)
    }
  }

  private[server] def registerAlice(
      peerId: Sha256Digest,
      registerInputs: RegisterInputs): Future[FieldElement] = {
    logger.info(s"Alice ${peerId.hex} is registering inputs")
    logger.debug(s"Alice's ${peerId.hex} inputs $registerInputs")

    require(registerInputs.inputs.forall(
              _.output.scriptPubKey.scriptType == WITNESS_V0_KEYHASH),
            s"${peerId.hex} attempted to register non p2wpkh inputs")

    val roundDbF = roundDAO.read(currentRoundId).map {
      case None => throw new RuntimeException("No roundDb found")
      case Some(roundDb) =>
        require(roundDb.status == RegisterAlices,
                s"Round in incorrect state ${roundDb.status}")
        roundDb
    }

    val aliceDbF = aliceDAO.read(peerId)

    val verifyInputFs = registerInputs.inputs.map { inputRef: InputReference =>
      import inputRef._
      for {
        banDbOpt <- bannedUtxoDAO.read(outPoint)
        notBanned = banDbOpt match {
          case Some(banDb) =>
            TimeUtil.now.isAfter(banDb.bannedUntil)
          case None => true
        }

        txResult <- bitcoind.getRawTransaction(outPoint.txIdBE)
        txOutT = Try(txResult.vout(outPoint.vout.toInt))
        isRealInput = txOutT match {
          case Failure(_) => false
          case Success(out) =>
            val spk = ScriptPubKey.fromAsmHex(out.scriptPubKey.hex)
            TransactionOutput(out.value, spk) == output
        }

        aliceDb <- aliceDbF
        peerNonce = aliceDb match {
          case Some(db) => db.nonce
          case None =>
            throw new IllegalArgumentException(
              s"No alice found with ${peerId.hex}")
        }
        validProof = InputReference.verifyInputProof(inputRef, peerNonce)
      } yield notBanned && isRealInput && validProof
    }

    val f = for {
      verifyInputs <- Future.sequence(verifyInputFs)
      roundDb <- roundDbF
    } yield (verifyInputs, roundDb)

    f.flatMap { case (verifyInputVec, roundDb) =>
      val validInputs = verifyInputVec.forall(v => v)

      val inputAmt = registerInputs.inputs.map(_.output.value).sum
      val inputFees = Satoshis(registerInputs.inputs.size) * roundDb.inputFee
      val outputFees = Satoshis(2) * roundDb.outputFee
      val onChainFees = inputFees + outputFees
      val changeAmt = inputAmt - roundDb.amount - roundDb.mixFee - onChainFees

      val validChange =
        registerInputs.changeOutput.value <= changeAmt &&
          registerInputs.changeOutput.scriptPubKey.scriptType == WITNESS_V0_KEYHASH

      if (validInputs && validChange) {
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
                                    changeOutput = registerInputs.changeOutput,
                                    blindOutputSig = sig)

          for {
            _ <- aliceDAO.update(updated)
            _ <- inputsDAO.createAll(inputDbs)
            registered <- aliceDAO.numRegisteredForRound(currentRoundId)

            // check if we need to stop waiting for peers
            _ <-
              if (registered >= config.maxPeers) {
                beginOutputRegistration()
              } else Future.unit
          } yield sig
        }
      } else {
        val bannedUntil = TimeUtil.now.plusSeconds(3600) // 1 hour

        val banDbs = registerInputs.inputs
          .map(_.outPoint)
          .map(
            BannedUtxoDb(_, bannedUntil, "Invalid inputs and proofs received"))

        bannedUtxoDAO
          .createAll(banDbs)
          .flatMap(_ =>
            Future.failed(
              new IllegalArgumentException(
                "Alice registered with invalid inputs")))
      }
    }
  }

  private[server] def verifyAndRegisterBob(bob: BobMessage): Future[Unit] = {
    logger.info("A bob is registering an output")
    if (bob.verifySigAndOutput(km.publicKey, currentRoundId)) {
      val db = RegisteredOutputDb(bob.output, bob.sig, currentRoundId)
      for {
        roundOpt <- roundDAO.read(currentRoundId)
        _ = roundOpt match {
          case Some(round) =>
            require(round.status == RegisterOutputs,
                    s"Round is in invalid state ${round.status}")
            require(round.amount == bob.output.value,
                    "Output given is incorrect amount")
          case None =>
            throw new RuntimeException(
              s"No round found for roundId ${currentRoundId.hex}")
        }
        _ <- outputsDAO.create(db)

        registeredAlices <- aliceDAO.numRegisteredForRound(currentRoundId)
        outputs <- outputsDAO.findByRoundId(currentRoundId)
      } yield {
        if (outputs.size >= registeredAlices) {
          outputsRegisteredP.success(())
        }
        ()
      }
    } else {
      Future.failed(
        new IllegalArgumentException(
          s"Received invalid signature for output ${bob.output}"))
    }
  }

  private[coordinator] def constructUnsignedPSBT(
      mixAddr: BitcoinAddress): Future[PSBT] = {
    val dbsF = for {
      aliceDbs <- aliceDAO.findRegisteredForRound(currentRoundId)
      inputDbs <- inputsDAO.findByRoundId(currentRoundId)
      outputDbs <- outputsDAO.findByRoundId(currentRoundId)
      roundDb <- roundDAO.read(currentRoundId).map(_.get)
    } yield (aliceDbs, inputDbs, outputDbs, roundDb)

    dbsF.flatMap { case (aliceDbs, inputDbs, outputDbs, roundDb) =>
      val txBuilder = RawTxBuilder().setFinalizer(
        FilterDustFinalizer.andThen(ShuffleFinalizer))

      // add inputs
      txBuilder ++= inputDbs.map(_.transactionInput)

      // add outputs
      val mixFee = Satoshis(aliceDbs.size) * config.mixFee
      val mixFeeOutput = TransactionOutput(mixFee, mixAddr.scriptPubKey)
      val mixOutputs = outputDbs.map(_.output)
      val changeOutputs = aliceDbs.map(_.changeOutputOpt.get)
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
      roundOpt <- roundDAO.read(currentRoundId)
      aliceDbOpt <- aliceDAO.read(peerId)
      inputs <- inputsDAO.findByPeerId(peerId, currentRoundId)
    } yield (roundOpt, aliceDbOpt, inputs)

    dbsF.flatMap {
      case (None, _, _) =>
        signedPMap(peerId).failure(
          new RuntimeException(s"No round found with id ${currentRoundId.hex}"))
        Future.failed(
          new RuntimeException(s"No round found with id ${currentRoundId.hex}"))
      case (_, None, _) =>
        Future.failed(
          new RuntimeException(s"No alice found with id ${peerId.hex}"))
      case (Some(roundDb), Some(aliceDb), inputs) =>
        require(roundDb.status == SigningPhase)
        roundDb.psbtOpt match {
          case Some(unsignedPsbt) =>
            val correctNumInputs = inputs.size == aliceDb.numInputs
            val sameTx = unsignedPsbt.transaction == psbt.transaction
            lazy val verify =
              inputs.map(_.indexOpt.get).forall(psbt.verifyFinalizedInput)

            if (correctNumInputs && sameTx && verify) {
              // mark successful
              signedPMap(peerId).success(psbt)
              val signedFs = signedPMap.values.map(_.future)

              val signedT = Try {
                val psbts =
                  Await.result(Future.sequence(signedFs), config.signingTime)

                val head = psbts.head
                val combined = psbts.tail.foldLeft(head)(_.combinePSBT(_))

                combined.extractTransactionAndValidate
              }.flatten

              // make promise complete
              completedTxP.tryComplete(signedT.map((_, signedFs.size)))

              Future.fromTry(signedT)
            } else {
              val bannedUntil = TimeUtil.now.plusSeconds(86400) // 1 day

              val dbs = inputs
                .map(_.outPoint)
                .map(BannedUtxoDb(_, bannedUntil, "Invalid psbt signature"))

              signedPMap(peerId).failure(
                new RuntimeException("Invalid psbt signature"))

              bannedUtxoDAO
                .createAll(dbs)
                .flatMap(_ =>
                  Future.failed(
                    new IllegalArgumentException(
                      s"Received invalid signature from peer ${peerId.hex}")))
            }
          case None =>
            signedPMap(peerId).failure(
              new RuntimeException("Round in invalid state, no psbt"))
            Future.failed(
              new RuntimeException("Round in invalid state, no psbt"))
        }
    }
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

  private[coordinator] lazy val serverBindF: Future[
    (InetSocketAddress, ActorRef)] = {
    logger.info(
      s"Binding coordinator to ${config.listenAddress}, with tor hidden service: ${config.torParams.isDefined}")

    val bindF = VortexServer.bind(vortexCoordinator = this,
                                  bindAddress = config.listenAddress,
                                  torParams = None)

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
    serverBindF.map { case (_, actorRef) =>
      system.stop(actorRef)
    }
  }

  def getHostAddress: Future[InetSocketAddress] = {
    hostAddressP.future
  }
}
