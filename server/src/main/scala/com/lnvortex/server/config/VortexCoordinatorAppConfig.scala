package com.lnvortex.server.config

import akka.actor.ActorSystem
import com.lnvortex.server.models._
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import org.bitcoins.commons.config._
import org.bitcoins.core.config._
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.core.wallet.fee._
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto._
import org.bitcoins.db._
import org.bitcoins.feeprovider._
import org.bitcoins.feeprovider.MempoolSpaceTarget._
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.keymanager.config.KeyManagerAppConfig
import org.bitcoins.tor.TorParams
import org.bitcoins.tor.config.TorAppConfig

import java.net.{InetSocketAddress, URI}
import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Properties, Random}

/** Configuration for Ln Vortex
  *
  * @param directory The data directory of the wallet
  * @param configOverrides Optional sequence of configuration overrides
  */
case class VortexCoordinatorAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[VortexCoordinatorAppConfig]
    with DbManagement
    with Logging {
  import system.dispatcher

  override val moduleName: String = VortexCoordinatorAppConfig.moduleName
  override type ConfigType = VortexCoordinatorAppConfig

  override val appConfig: VortexCoordinatorAppConfig = this

  import profile.api._

  override def newConfigOfType(
      configs: Vector[Config]): VortexCoordinatorAppConfig =
    VortexCoordinatorAppConfig(directory, configs)

  override val baseDatadir: Path = directory

  override def start(): Future[Unit] = FutureUtil.makeAsync { () =>
    logger.info(s"Initializing coordinator")

    if (Files.notExists(baseDatadir)) {
      Files.createDirectories(baseDatadir)
    }

    val numMigrations = migrate().migrationsExecuted
    logger.debug(s"Applied $numMigrations")

    initialize()
  }

  lazy val torConf: TorAppConfig =
    TorAppConfig(directory, None, configOverrides)

  lazy val kmConf: KeyManagerAppConfig =
    KeyManagerAppConfig(directory, configOverrides)

  lazy val torParams: Option[TorParams] = torConf.torParams

  lazy val listenAddress: InetSocketAddress = {
    val str = config.getString(s"$moduleName.listen")
    val uri = new URI("tcp://" + str)
    new InetSocketAddress(uri.getHost, uri.getPort)
  }

  lazy val kmParams: KeyManagerParams =
    KeyManagerParams(kmConf.seedPath, HDPurposes.SegWit, network)

  lazy val aesPasswordOpt: Option[AesPassword] = kmConf.aesPasswordOpt
  lazy val bip39PasswordOpt: Option[String] = kmConf.bip39PasswordOpt

  lazy val inputScriptType: ScriptType = {
    val str = config.getString(s"$moduleName.inputScriptType")
    ScriptType.fromString(str)
  }

  lazy val changeScriptType: ScriptType = {
    val str = config.getString(s"$moduleName.changeScriptType")
    ScriptType.fromString(str)
  }

  lazy val outputScriptType: ScriptType = {
    val str = config.getString(s"$moduleName.outputScriptType")
    ScriptType.fromString(str)
  }

  lazy val minRemixPeers: Int = {
    config.getInt(s"$moduleName.minRemixPeers")
  }

  lazy val minNewPeers: Int = {
    config.getInt(s"$moduleName.minNewPeers")
  }

  lazy val maxPeers: Int = {
    config.getInt(s"$moduleName.maxPeers")
  }

  lazy val minPeers: Int = minRemixPeers + minNewPeers

  lazy val roundAmount: Satoshis = {
    val long = config.getLong(s"$moduleName.roundAmount")
    Satoshis(long)
  }

  lazy val coordinatorFee: Satoshis = {
    val long = config.getLong(s"$moduleName.coordinatorFee")
    Satoshis(long)
  }

  lazy val roundInterval: FiniteDuration = {
    val dur = config.getDuration(s"$moduleName.roundInterval")
    FiniteDuration(dur.getSeconds, SECONDS)
  }

  lazy val statusString: String = {
    config.getStringOrElse(s"$moduleName.statusString", "")
  }

  lazy val inputRegistrationTime: FiniteDuration = {
    val dur = config.getDuration(s"$moduleName.inputRegistrationTime")
    FiniteDuration(dur.getSeconds, SECONDS)
  }

  lazy val outputRegistrationTime: FiniteDuration = {
    val dur = config.getDuration(s"$moduleName.outputRegistrationTime")
    FiniteDuration(dur.getSeconds, SECONDS)
  }

  lazy val signingTime: FiniteDuration = {
    val dur = config.getDuration(s"$moduleName.signingTime")
    FiniteDuration(dur.getSeconds, SECONDS)
  }

  lazy val badInputsBanDuration: FiniteDuration = {
    val dur = config.getDuration(s"$moduleName.badInputsBanDuration")
    FiniteDuration(dur.getSeconds, SECONDS)
  }

  lazy val invalidSignatureBanDuration: FiniteDuration = {
    val dur = config.getDuration(s"$moduleName.invalidSignatureBanDuration")
    FiniteDuration(dur.getSeconds, SECONDS)
  }

  private val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, network, None)

  private val feeProviderBackup: BitcoinerLiveFeeRateProvider =
    BitcoinerLiveFeeRateProvider(30, None)

  private val random = new Random(System.currentTimeMillis())

  def fetchFeeRate(): Future[SatoshisPerVirtualByte] = {
    network match {
      case MainNet | TestNet3 | SigNet =>
        feeProvider.getFeeRate().recoverWith { case _: Throwable =>
          feeProviderBackup.getFeeRate()
        }
      case RegTest =>
        val rand = random.nextInt() % 50
        val max = Math.max(Math.abs(rand), 1)
        Future.successful(SatoshisPerVirtualByte.fromLong(max))
    }
  }

  def initialize(): Unit = {
    if (!kmConf.seedExists()) {
      BIP39KeyManager.initialize(aesPasswordOpt = aesPasswordOpt,
                                 kmParams = kmParams,
                                 bip39PasswordOpt = bip39PasswordOpt) match {
        case Left(err) => sys.error(err.toString)
        case Right(_) =>
          logger.info("Successfully generated a seed and key manager")
      }
    }
  }

  override lazy val allTables: List[TableQuery[Table[_]]] = {
    List(
      BannedUtxoDAO()(dispatcher, this).table,
      RoundDAO()(dispatcher, this).table,
      AliceDAO()(dispatcher, this).table,
      RegisteredInputDAO()(dispatcher, this).table,
      RegisteredOutputDAO()(dispatcher, this).table
    )
  }
}

object VortexCoordinatorAppConfig
    extends AppConfigFactoryBase[VortexCoordinatorAppConfig, ActorSystem] {
  override val moduleName: String = "coordinator"

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".ln-vortex")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): VortexCoordinatorAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): VortexCoordinatorAppConfig =
    VortexCoordinatorAppConfig(datadir, confs)
}
