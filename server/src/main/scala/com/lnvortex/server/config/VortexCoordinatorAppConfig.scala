package com.lnvortex.server.config

import akka.actor.ActorSystem
import com.lnvortex.core.VortexUtils
import com.lnvortex.core.api.CoordinatorAddress
import com.lnvortex.server.models._
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import monix.execution.atomic.AtomicInt
import org.bitcoins.commons.config._
import org.bitcoins.core.config._
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util._
import org.bitcoins.core.wallet.fee._
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto._
import org.bitcoins.db.DatabaseDriver._
import org.bitcoins.db._
import org.bitcoins.feeprovider.MempoolSpaceTarget._
import org.bitcoins.feeprovider._
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.keymanager.config.KeyManagerAppConfig
import org.bitcoins.keymanager.config.KeyManagerAppConfig._
import org.bitcoins.tor.TorParams
import org.bitcoins.tor.config.TorAppConfig

import java.io.File
import java.net.InetSocketAddress
import java.nio.file._
import scala.concurrent._
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util._

/** Configuration for Ln Vortex
  *
  * @param directory
  *   The data directory of the wallet
  * @param configOverrides
  *   Optional sequence of configuration overrides
  */
case class VortexCoordinatorAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[VortexCoordinatorAppConfig]
    with DbManagement
    with VortexUtils
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

  override def configFileName: String = CONFIG_FILE_NAME

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

  lazy val torParams: Option[TorParams] = {
    torConf.torParams.map { params =>
      val privKeyPath =
        config.getStringOrNone("bitcoin-s.tor.privateKeyPath") match {
          case Some(path) => new File(path).toPath
          case None =>
            val fileName = {
              val prefix =
                if (coordinatorName == DEFAULT_WALLET_NAME) ""
                else s"${coordinatorName}_"

              s"$prefix${network}_tor_priv_key"
            }
            baseDatadir.resolve("torKeys").resolve(fileName)
        }

      params.copy(privateKeyPath = privKeyPath)
    }
  }

  torParams.map(_.privateKeyPath)

  lazy val listenAddress: InetSocketAddress = {
    val str = config.getString(s"$moduleName.listen")
    NetworkUtil.parseInetSocketAddress(str, DEFAULT_PORT)
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

  lazy val coordinatorName: String = {
    config.getStringOrElse(s"$moduleName.name", DEFAULT_WALLET_NAME)
  }

  override lazy val dbPath: Path = {
    val pathStrOpt =
      config.getStringOrNone(s"bitcoin-s.$moduleName.db.path")
    pathStrOpt match {
      case Some(pathStr) =>
        Paths.get(pathStr).resolve(coordinatorName)
      case None =>
        sys.error(s"Could not find dbPath for $moduleName.db.path")
    }
  }

  override lazy val schemaName: Option[String] = {
    driver match {
      case PostgreSQL =>
        val schema = PostgresUtil.getSchemaName(moduleName = moduleName,
                                                walletName = coordinatorName)
        Some(schema)
      case SQLite => None
    }
  }

  private val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, network, None)

  private val feeProviderBackup: BitcoinerLiveFeeRateProvider =
    BitcoinerLiveFeeRateProvider(30, None)

  private lazy val random = new Random(System.currentTimeMillis())
  private lazy val prevFeeRate = AtomicInt(0)

  def fetchFeeRate(): Future[SatoshisPerVirtualByte] = {
    network match {
      case MainNet | TestNet3 | SigNet =>
        logger.trace("Fetching fee rate")
        feeProvider.getFeeRate().recoverWith { case _: Throwable =>
          logger.trace("Fetching fee rate from backup provider")
          feeProviderBackup.getFeeRate()
        }
      case RegTest =>
        val rand = random.nextInt() % 50
        val floor = Math.max(Math.abs(rand), 1)
        logger.trace(s"Generated random fee rate $floor")
        if (prevFeeRate.get() == floor) {
          fetchFeeRate()
        } else {
          prevFeeRate.set(floor)
          Future.successful(SatoshisPerVirtualByte.fromLong(floor))
        }
    }
  }

  def initialize(): Unit = {
    // initialize seed
    if (!kmConf.seedExists()) {
      BIP39KeyManager.initialize(aesPasswordOpt = aesPasswordOpt,
                                 kmParams = kmParams,
                                 bip39PasswordOpt = bip39PasswordOpt) match {
        case Left(err) => sys.error(err.toString)
        case Right(_) =>
          logger.info("Successfully generated a seed and key manager")
      }
    }

    // initialize tor keys
    if (
      torConf.enabled && !torParams
        .map(_.privateKeyPath)
        .exists(Files.exists(_))
    ) {
      val path = torParams.map(_.privateKeyPath).get
      Files.createDirectories(path.getParent)
    }

    ()
  }

  lazy val coordinatorAddresses: Map[BitcoinNetwork,
                                     Vector[CoordinatorAddress]] = {
    val coordinators =
      config.getConfigList(s"vortex.coordinators").asScala.toList

    val list = for {
      coordinatorConfig <- coordinators
      name = coordinatorConfig.getString("name")
      networkStr = coordinatorConfig.getString("network")
      onion = coordinatorConfig.getString("onion")
    } yield CoordinatorAddress(name,
                               BitcoinNetworks.fromString(networkStr),
                               NetworkUtil.parseInetSocketAddress(onion, 12523))

    list.toVector.groupBy(_.network)
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
