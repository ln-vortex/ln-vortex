package com.lnvortex.server.coordinator

import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.server.VortexServerException.InvalidBlindChallengeException
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.models.AliceDAO
import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.crypto.ExtPrivateKeyHardened
import org.bitcoins.core.hd._
import org.bitcoins.crypto._
import org.bitcoins.keymanager._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent._

class CoordinatorKeyManager()(implicit
    ec: ExecutionContext,
    val config: VortexCoordinatorAppConfig)
    extends Logging {

  private[server] val aliceDAO = AliceDAO()

  import aliceDAO.profile.api._

  /** The root private key for this coordinator */
  private[this] lazy val extPrivateKey: ExtPrivateKeyHardened =
    WalletStorage.getPrivateKeyFromDisk(config.seedPath,
                                        SegWitMainNetPriv,
                                        config.aesPasswordOpt,
                                        config.bip39PasswordOpt)

  private val coinType = HDCoinType.fromNetwork(config.network)

  private val pubKeyPath = BIP32Path.fromHardenedString(
    s"m/${CoordinatorKeyManager.PURPOSE.constant}'/${coinType.toInt}'/0'")

  private lazy val nonceCounter: DBIOAction[AtomicInteger,
                                            NoStream,
                                            Effect.Read] = {
    aliceDAO.nextNonceIndexAction().map { index =>
      new AtomicInteger(index)
    }
  }

  private def nextNoncePath(): DBIOAction[BIP32Path, NoStream, Effect.Read] = {
    nonceCounter.map { counter =>
      val purpose = HDPurposes.Legacy
      val coin = HDCoin(purpose, coinType)
      val account = HDAccount(coin, 0)
      val hdChain = HDChain(HDChainType.External, account)
      val path = HDAddress(hdChain, counter.getAndIncrement()).toPath
      // make hardened for security
      // this is very important, otherwise can leak key data
      val hardened = path.map(_.copy(hardened = true))

      BIP32Path(hardened.toVector)
    }
  }

  final private[server] def nextNonce(): DBIOAction[(SchnorrNonce, BIP32Path),
                                                    NoStream,
                                                    Effect.Read] = {
    logger.trace("Getting next nonce for alice")
    nextNoncePath().map { path =>
      val nonce = extPrivateKey.deriveChildPrivKey(path).key.schnorrNonce

      (nonce, path)
    }
  }

  private[server] lazy val privKey: ECPrivateKey =
    extPrivateKey.deriveChildPrivKey(pubKeyPath).key

  lazy val publicKey: SchnorrPublicKey = privKey.schnorrPublicKey

  def createBlindSig(
      blindedOutput: FieldElement,
      noncePath: BIP32Path): FieldElement = {
    val nonceKey =
      extPrivateKey.deriveChildPrivKey(noncePath).key

    try {
      BlindSchnorrUtil.generateBlindSig(privKey, nonceKey, blindedOutput)
    } catch {
      case _: IllegalArgumentException =>
        throw new InvalidBlindChallengeException(
          s"Got a blinded challenge of $blindedOutput")
    }
  }
}

object CoordinatorKeyManager {
  final val PURPOSE: HDPurpose = HDPurpose(69)
}
