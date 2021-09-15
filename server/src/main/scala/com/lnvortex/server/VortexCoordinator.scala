package com.lnvortex.server

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.models._
import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.crypto.{BIP39Seed, ExtPrivateKey}
import org.bitcoins.core.currency.CurrencyUnit
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
  private[server] val roundDAO = RoundDAO()

  /** The root private key for this oracle */
  private[this] val extPrivateKey: ExtPrivateKey = {
    val decryptedSeed = WalletStorage.decryptSeedFromDisk(
      config.seedPath,
      config.aesPasswordOpt) match {
      case Left(error)     => sys.error(error.toString)
      case Right(mnemonic) => mnemonic
    }

    decryptedSeed match {
      case DecryptedMnemonic(mnemonicCode, _) =>
        val seed = BIP39Seed.fromMnemonic(mnemonicCode, config.bip39PasswordOpt)
        seed.toExtPrivateKey(SegWitMainNetPriv)
      case DecryptedExtPrivKey(xprv, _) => xprv
    }
  }

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

  def inputFee: CurrencyUnit = feeRate * 148 // p2wpkh input size
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

  private[server] val clientDetailsMap: mutable.Map[
    Sha256Digest,
    ClientDetails] = mutable.Map.empty

  private[server] val registeredOutputs: mutable.Map[
    SchnorrDigitalSignature,
    TransactionOutput] = mutable.Map.empty

  def getAdvertisement(
      id: Sha256Digest,
      connectionHandler: ActorRef): MixAdvertisement = {
    clientDetailsMap.get(id) match {
      case Some(details) => advTemplate.copy(nonce = details.nonce)
      case None =>
        val (nonce, path) = nextNonce()

        val newDetail = Advertised(id, connectionHandler, nonce, path)
        val _ = clientDetailsMap.put(id, newDetail)

        advTemplate.copy(nonce = nonce)
    }
  }

  def nextRound(): Unit = {
    advTemplate = advTemplate.copy(time = UInt64(nextRoundTime))
    clientDetailsMap.clear()
  }

  def registerAlice(
      id: Sha256Digest,
      aliceInit: AliceInit): Future[AliceInitResponse] = {
    require(aliceInit.inputs.forall(
              _.output.scriptPubKey.scriptType == WITNESS_V0_KEYHASH),
            s"${id.hex} attempted to register non p2wpkh inputs")

    val verifyInputFs = aliceInit.inputs.map {
      case OutputReference(outPoint, output) =>
        for {
          notBanned <- bannedUtxoDAO.read(outPoint).map(_.isEmpty)

          txResult <- bitcoind.getRawTransaction(outPoint.txIdBE)
          isRealInput = txResult.vout.exists(out =>
            TransactionOutput(out.value,
                              ScriptPubKey(out.scriptPubKey.hex)) == output)
        } yield notBanned && isRealInput
    }

    Future.sequence(verifyInputFs).map { verifyInputs =>
      val verify = verifyInputs.forall(v => v)

      if (verify) {
        // todo verify change output isn't too large

        clientDetailsMap(id) match {
          case details: Advertised =>
            val nonceKey =
              extPrivateKey.deriveChildPrivKey(details.noncePath).key
            val sig = BlindSchnorrUtil.generateBlindSig(privKey,
                                                        nonceKey,
                                                        aliceInit.blindedOutput)

            clientDetailsMap.put(id, details.toInitialized(aliceInit))

            AliceInitResponse(sig)
          case state @ (_: Initialized | _: Unsigned | _: Signed) =>
            sys.error(s"Alice is already registered at state $state")
        }
      } else {
        throw new RuntimeException("Received invalid inputs")
      }
    }
  }

  def verifyAndRegisterBob(bob: BobMessage): Boolean = {
    if (bob.verifySigAndOutput(publicKey)) {
      registeredOutputs.put(bob.sig, bob.output)
      true
    } else {
      logger.warn(s"Received invalid signature for output ${bob.output}")
      false
    }
  }

  private[server] def constructUnsignedTransaction(
      mixAddr: BitcoinAddress): Transaction = {
    val initialized = clientDetailsMap
      .filter(_._2.isInitialized)
      .toVector
      .flatMap { case (id, details) =>
        details match {
          case init: Initialized => Some(init)
          case _: Advertised | _: Unsigned | _: Signed =>
            clientDetailsMap.remove(id)
            None
        }
      }

    val txBuilder = RawTxBuilder().setFinalizer(ShuffleFinalizer)

    // add mix outputs
    txBuilder ++= registeredOutputs.values.toVector
    // add inputs
    txBuilder ++= initialized.flatMap(_.aliceInit.inputs).map {
      outputReference =>
        TransactionInput(outputReference.outPoint,
                         EmptyScriptSignature,
                         TransactionConstants.sequence)
    }
    // add change outputs
    txBuilder ++= initialized.map(_.aliceInit.changeOutput)

    // add mix fee output
    val mixFee = initialized.size * config.mixFee
    txBuilder += TransactionOutput(mixFee, mixAddr.scriptPubKey)

    val transaction = txBuilder.buildTx()

    initialized.foreach { init =>
      val indexes = init.aliceInit.inputs.map { ref =>
        transaction.inputs.map(_.previousOutput).indexOf(ref.outPoint)
      }
      val psbt = PSBT.fromUnsignedTx(transaction)
      init.toUnsigned(psbt, Promise[PSBT](), indexes)
    }

    transaction
  }

  def registerPSBTSignature(id: Sha256Digest, psbt: PSBT): Try[Transaction] = {
    val details = clientDetailsMap(id)

    details match {
      case state @ (_: Advertised | _: Initialized | _: Signed) =>
        sys.error(s"Alice is at invalid state $state")
      case unsigned: Unsigned =>
        val sameTx = unsigned.unsignedPSBT.transaction == psbt.transaction
        lazy val verify = unsigned.indexes.forall(psbt.verifyFinalizedInput)
        if (sameTx && verify) {
          val details = unsigned.toSigned(psbt)
          clientDetailsMap.put(id, details)

          val fs = clientDetailsMap.values.map {
            case state @ (_: Initialized | _: Advertised) =>
              throw new RuntimeException(s"Got client at state $state")
            case ready: ReadyToSign =>
              ready.signedP.future
          }
          Try {
            val psbts = Await.result(Future.sequence(fs), 60.seconds)

            val head = psbts.head
            val combined = psbts.tail.foldLeft(head)(_.combinePSBT(_))

            combined.extractTransactionAndValidate
          }.flatten
        } else {
          val bannedUntil = TimeUtil.now.plusSeconds(86400) // 1 day

          val dbs = unsigned.aliceInit.inputs
            .map(_.outPoint)
            .map(BannedUtxoDb(_, bannedUntil, "Invalid psbt signature"))

          // dropping this future, maybe should fix
          bannedUtxoDAO.createAll(dbs)

          unsigned.signedP.failure(
            new RuntimeException("Invalid input signature"))

          Failure(
            new IllegalArgumentException(
              "Received invalid signature from peer"))
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
