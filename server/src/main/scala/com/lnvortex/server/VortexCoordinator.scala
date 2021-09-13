package com.lnvortex.server

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.crypto.ExtPrivateKeyHardened
import org.bitcoins.core.hd.BIP32Path
import org.bitcoins.core.number.{UInt16, UInt64}
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.{StartStopAsync, TimeUtil}
import org.bitcoins.crypto._
import org.bitcoins.keymanager.WalletStorage
import org.bitcoins.lnd.rpc.LndRpcClient

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import scala.util.{Failure, Success}

case class VortexCoordinator(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem,
    val config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {
  import system.dispatcher

  final val version = UInt16.zero

  /** The root private key for this oracle */
  private[this] val extPrivateKey: ExtPrivateKeyHardened = {
    WalletStorage.getPrivateKeyFromDisk(config.seedPath,
                                        SegWitMainNetPriv,
                                        config.aesPasswordOpt,
                                        config.bip39PasswordOpt)
  }

  private val pubKeyPath = BIP32Path.fromHardenedString("m/69'/0'/0'")

  private val nonceCounter: AtomicInteger =
    new AtomicInteger(0) // todo read from db

  private def nextNoncePath: BIP32Path = BIP32Path.fromHardenedString(
    s"m/69'/0'/0'/${nonceCounter.getAndIncrement()}")

  @tailrec
  private def nextNonce(): (SchnorrNonce, BIP32Path) = {
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

  private def nextRoundTime: Long =
    TimeUtil.currentEpochSecond + config.interval.getSeconds

  private var advTemplate: MixAdvertisement =
    MixAdvertisement(
      version = version,
      amount = config.mixAmount,
      fee = config.mixFee,
      publicKey = publicKey,
      nonce = ECPublicKey.freshPublicKey.schnorrNonce,
      time = UInt64(nextRoundTime)
    )

  private val clientDetailsMap: mutable.Map[Sha256Digest, ClientDetails] =
    mutable.Map.empty

  private val registeredOutputs: mutable.Map[
    SchnorrDigitalSignature,
    TransactionOutput] = mutable.Map.empty

  def getAdvertisement(
      id: Sha256Digest,
      connectionHandler: ActorRef): MixAdvertisement = {
    val (nonce, path) = nextNonce()

    clientDetailsMap.put(id, Advertised(id, connectionHandler, nonce, path))

    advTemplate.copy(nonce = nonce)
  }

  def nextRound(): Unit = {
    advTemplate = advTemplate.copy(time = UInt64(nextRoundTime))
    clientDetailsMap.clear()
  }

  def registerAlice(
      id: Sha256Digest,
      aliceInit: AliceInit): AliceInitResponse = {
    val details = clientDetailsMap(id)

    details match {
      case details: Advertised =>
        val nonceKey = extPrivateKey.deriveChildPrivKey(details.noncePath).key
        val sig = BlindSchnorrUtil.generateBlindSig(privKey,
                                                    nonceKey,
                                                    aliceInit.blindedOutput)

        clientDetailsMap.put(id, details.toInitialized(aliceInit))

        AliceInitResponse(sig)
      case state @ (_: Initialized | _: Unsigned | _: Signed) =>
        sys.error(s"Alice is already registered at state $state")
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

  def registerPSBTSignature(id: Sha256Digest, psbt: PSBT): Boolean = {
    val details = clientDetailsMap(id)

    details match {
      case state @ (_: Advertised | _: Initialized | _: Signed) =>
        sys.error(s"Alice is at invalid state $state")
      case unsigned: Unsigned =>
        val verify = unsigned.indexes.forall(psbt.verifyFinalizedInput)
        if (verify) {
          clientDetailsMap.put(id, unsigned.toSigned(psbt))
          // todo schedule sending full tx
          true
        } else false
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
