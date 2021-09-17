package com.lnvortex.server

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.models._
import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.crypto.ExtPrivateKeyHardened
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.hd._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.{EmptyScriptSignature, ScriptPubKey}
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType.WITNESS_V0_KEYHASH
import org.bitcoins.core.util._
import org.bitcoins.core.wallet.builder._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.feeprovider.MempoolSpaceProvider
import org.bitcoins.feeprovider.MempoolSpaceTarget.FastestFeeTarget
import org.bitcoins.keymanager._
import org.bitcoins.rpc.client.common.BitcoindRpcClient

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

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

  /** The root private key for this oracle */
  private[this] val extPrivateKey: ExtPrivateKeyHardened =
    WalletStorage.getPrivateKeyFromDisk(config.seedPath,
                                        SegWitMainNetPriv,
                                        config.aesPasswordOpt,
                                        config.bip39PasswordOpt)

  private val pubKeyPath = BIP32Path.fromHardenedString("m/69'/0'/0'")

  private val hdChain = {
    val purpose = HDPurposes.Legacy
    val coin = HDCoin(purpose, HDCoinType.Bitcoin)
    val account = HDAccount(coin, 0)
    HDChain(HDChainType.External, account)
  }

  private val nonceCounter: AtomicInteger = {
    val startingIndex = Await.result(aliceDAO.nextNonceIndex(), 5.seconds)
    new AtomicInteger(startingIndex)
  }

  private def nextNoncePath: HDPath = {
    HDAddress(hdChain, nonceCounter.getAndIncrement()).toPath
  }

  @tailrec
  private def nextNonce(): (SchnorrNonce, HDPath) = {
    val path = nextNoncePath
    val nonceT = extPrivateKey.deriveChildPubKey(nextNoncePath)

    nonceT match {
      case Success(nonce) => (nonce.key.schnorrNonce, path)
      case Failure(_)     => nextNonce()
    }
  }

  private[this] val privKey: ECPrivateKey =
    extPrivateKey.deriveChildPrivKey(pubKeyPath).key

  val publicKey: SchnorrPublicKey = privKey.schnorrPublicKey

  val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, config.network, None)

  var feeRate: SatoshisPerVirtualByte = SatoshisPerVirtualByte.fromLong(10)

  var currentRoundId: Sha256Digest = Sha256Digest.empty

  def inputFee: CurrencyUnit = feeRate * 149 // p2wpkh input size
  def outputFee: CurrencyUnit = feeRate * 43 // p2wsh output size

  private def nextRoundTime: Long =
    TimeUtil.currentEpochSecond + config.interval.getSeconds

  private var advTemplate: MixAdvertisement =
    MixAdvertisement(
      version = version,
      amount = config.mixAmount,
      mixFee = config.mixFee,
      inputFee = inputFee,
      outputFee = outputFee,
      publicKey = publicKey,
      nonce = ECPublicKey.freshPublicKey.schnorrNonce,
      time = UInt64(nextRoundTime)
    )

  private[server] val connectionHandlerMap: mutable.Map[
    Sha256Digest,
    ActorRef] = mutable.Map.empty

  private[server] val signedPMap: mutable.Map[Sha256Digest, Promise[PSBT]] =
    mutable.Map.empty

  // todo update round dbs everywhere

  def getAdvertisement(
      peerId: Sha256Digest,
      connectionHandler: ActorRef): Future[MixAdvertisement] = {
    aliceDAO.read(peerId).flatMap {
      case Some(alice) =>
        Future.successful(advTemplate.copy(nonce = alice.nonce))
      case None =>
        val (nonce, path) = nextNonce()

        val aliceDb = AliceDbs.newAlice(peerId, currentRoundId, path, nonce)

        connectionHandlerMap.put(peerId, connectionHandler)

        aliceDAO.create(aliceDb).map(_ => advTemplate.copy(nonce = nonce))
    }
  }

  def newRound(): Unit = {
    advTemplate = advTemplate.copy(time = UInt64(nextRoundTime))
    connectionHandlerMap.clear()
    signedPMap.clear()
  }

  def registerAlice(
      peerId: Sha256Digest,
      aliceInit: AliceInit): Future[AliceInitResponse] = {
    require(aliceInit.inputs.forall(
              _.output.scriptPubKey.scriptType == WITNESS_V0_KEYHASH),
            s"${peerId.hex} attempted to register non p2wpkh inputs")

    val roundDbF = roundDAO.read(currentRoundId).map(_.get)
    val peerNonceF = aliceDAO.read(peerId).map(_.get.nonce)

    val verifyInputFs = aliceInit.inputs.map { inputRef: InputReference =>
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
            TransactionOutput(out.value,
                              ScriptPubKey(out.scriptPubKey.hex)) == output
        }

        peerNonce <- peerNonceF
        validProof = InputReference.verifyInputProof(inputRef, peerNonce)
      } yield notBanned && isRealInput && validProof
    }

    val f = for {
      verifyInputs <- Future.sequence(verifyInputFs)
      roundDb <- roundDbF
    } yield (verifyInputs, roundDb)

    f.flatMap { case (verifyInputVec, roundDb) =>
      val validInputs = verifyInputVec.forall(v => v)

      val inputAmt = aliceInit.inputs.map(_.output.value).sum
      val inputFees = Satoshis(aliceInit.inputs.size) * roundDb.inputFee
      val outputFees = Satoshis(2) * roundDb.outputFee
      val onChainFees = inputFees + outputFees
      val changeAmt = inputAmt - roundDb.amount - roundDb.mixFee - onChainFees

      val validChange =
        aliceInit.changeOutput.value <= changeAmt &&
          aliceInit.changeOutput.scriptPubKey.scriptType == WITNESS_V0_KEYHASH

      if (validInputs && validChange) {

        aliceDAO.read(peerId).flatMap {
          case Some(aliceDb) =>
            val nonceKey =
              extPrivateKey.deriveChildPrivKey(aliceDb.noncePath).key
            val sig = BlindSchnorrUtil
              .generateBlindSig(privKey, nonceKey, aliceInit.blindedOutput)

            val inputDbs = aliceInit.inputs.map(
              RegisteredInputDbs.fromInputReference(_, currentRoundId, peerId))

            val updated =
              aliceDb.copy(blindedOutputOpt = Some(aliceInit.blindedOutput),
                           changeOutputOpt = Some(aliceInit.changeOutput),
                           blindOutputSigOpt = Some(sig))

            for {
              _ <- aliceDAO.update(updated)
              _ <- inputsDAO.createAll(inputDbs)
            } yield AliceInitResponse(sig)
          case None =>
            Future.failed(
              new RuntimeException(s"No alice found with ${peerId.hex}"))
        }
      } else {

        val bannedUntil = TimeUtil.now.plusSeconds(3600) // 1 hour

        val banDbs = aliceInit.inputs
          .map(_.outPoint)
          .map(
            BannedUtxoDb(_, bannedUntil, "Invalid inputs and proofs received"))

        bannedUtxoDAO
          .createAll(banDbs)
          .flatMap(_ =>
            Future.failed(
              new RuntimeException("Alice registered with invalid inputs")))
      }
    }
  }

  def verifyAndRegisterBob(bob: BobMessage): Future[Boolean] = {
    if (bob.verifySigAndOutput(publicKey)) {
      val db = RegisteredOutputDb(bob.output, bob.sig, currentRoundId)
      outputsDAO.create(db).map(_ => true)
    } else {
      logger.warn(s"Received invalid signature for output ${bob.output}")
      Future.successful(false)
    }
  }

  private[server] def constructUnsignedTransaction(
      mixAddr: BitcoinAddress): Future[Transaction] = {
    val dbsF = for {
      inputDbs <- inputsDAO.findByRoundId(currentRoundId)
      outputDbs <- outputsDAO.findByRoundId(currentRoundId)
      roundDb <- roundDAO.read(currentRoundId).map(_.get)
    } yield (inputDbs, outputDbs, roundDb)

    dbsF.flatMap { case (inputDbs, outputDbs, roundDb) =>
      val txBuilder = RawTxBuilder().setFinalizer(
        FilterDustFinalizer.andThen(ShuffleFinalizer))

      // add mix outputs
      txBuilder ++= outputDbs.map(_.output)
      // add inputs & change outputs
      txBuilder ++= inputDbs.map { inputDb =>
        val input = TransactionInput(inputDb.outPoint,
                                     EmptyScriptSignature,
                                     TransactionConstants.sequence)
        (input, inputDb.output)
      }

      // add mix fee output
      val mixFee = inputDbs.size * config.mixFee
      txBuilder += TransactionOutput(mixFee, mixAddr.scriptPubKey)

      val transaction = txBuilder.buildTx()

      val outPoints = transaction.inputs.map(_.previousOutput)

      val psbt = PSBT.fromUnsignedTx(transaction)

      val updatedRound = roundDb.copy(psbtOpt = Some(psbt))
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
      } yield transaction
    }
  }

  def registerPSBTSignature(
      peerId: Sha256Digest,
      psbt: PSBT): Future[Transaction] = {

    val dbsF = for {
      roundOpt <- roundDAO.read(currentRoundId)
      inputs <- inputsDAO.findByRoundId(currentRoundId)
    } yield (roundOpt, inputs.filter(_.indexOpt.isDefined))

    dbsF.flatMap {
      case (None, _) =>
        Future.failed(
          new RuntimeException(s"No round found with id ${currentRoundId.hex}"))
      case (Some(roundDb), inputs) =>
        roundDb.psbtOpt match {
          case Some(unsignedPsbt) =>
            require(inputs.size == unsignedPsbt.inputMaps.size)
            val sameTx = unsignedPsbt.transaction == psbt.transaction
            lazy val verify =
              inputs.flatMap(_.indexOpt).forall(psbt.verifyFinalizedInput)
            if (sameTx && verify) {

              val signedFs = signedPMap.values.map(_.future)

              val signedT = Try {
                val psbts = Await.result(Future.sequence(signedFs), 60.seconds)

                val head = psbts.head
                val combined = psbts.tail.foldLeft(head)(_.combinePSBT(_))

                combined.extractTransactionAndValidate
              }.flatten

              Future.fromTry(signedT)
            } else {
              val bannedUntil = TimeUtil.now.plusSeconds(86400) // 1 day

              val dbs = inputs
                .map(_.outPoint)
                .map(BannedUtxoDb(_, bannedUntil, "Invalid psbt signature"))

              signedPMap(peerId).failure(
                new RuntimeException("Invalid input signature"))

              bannedUtxoDAO
                .createAll(dbs)
                .flatMap(_ =>
                  Future.failed(
                    new IllegalArgumentException(
                      s"Received invalid signature from peer ${peerId.hex}")))
            }
          case None =>
            signedPMap(peerId).failure(
              new RuntimeException("Round in valid state"))
            Future.failed(new RuntimeException("Round in valid state"))
        }
    }
  }

  def updateFeeRate(): Future[Unit] = {
    feeProvider.getFeeRate.map { res =>
      feeRate = res
    }
  }

  // -- Server startup logic --

  private val hostAddressP: Promise[InetSocketAddress] =
    Promise[InetSocketAddress]()

  private[node] lazy val serverBindF: Future[(InetSocketAddress, ActorRef)] = {
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
    serverBindF.map(_ => ())
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
