package com.lnvortex.server.config

import akka.actor.ActorSystem
import com.lnvortex.core.InputRegistrationType
import com.lnvortex.server.models._
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import org.bitcoins.commons.config._
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto._
import org.bitcoins.db._
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.keymanager.config.KeyManagerAppConfig
import org.bitcoins.tor.config.TorAppConfig
import org.bitcoins.tor.{Socks5ProxyParams, TorParams}

import java.net.{InetSocketAddress, URI}
import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Properties

/** Configuration for Ln Vortex
  *
  * @param directory The data directory of the wallet
  * @param conf      Optional sequence of configuration overrides
  */
case class VortexCoordinatorAppConfig(
    private val directory: Path,
    private val conf: Config*)(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[VortexCoordinatorAppConfig]
    with DbManagement
    with Logging {
  import system.dispatcher

  override val configOverrides: List[Config] = conf.toList
  override val moduleName: String = VortexCoordinatorAppConfig.moduleName
  override type ConfigType = VortexCoordinatorAppConfig

  override val appConfig: VortexCoordinatorAppConfig = this

  import profile.api._

  override def newConfigOfType(
      configs: Seq[Config]): VortexCoordinatorAppConfig =
    VortexCoordinatorAppConfig(directory, configs: _*)

  override val baseDatadir: Path = directory

  override def start(): Future[Unit] = {
    logger.info(s"Initializing coordinator")

    if (Files.notExists(baseDatadir)) {
      Files.createDirectories(baseDatadir)
    }

    val numMigrations = migrate()
    logger.debug(s"Applied $numMigrations")

    initialize()

    Future.unit
  }

  override def stop(): Future[Unit] = Future.unit

  lazy val torConf: TorAppConfig =
    TorAppConfig(directory, None, conf: _*)

  lazy val kmConf: KeyManagerAppConfig =
    KeyManagerAppConfig(directory, conf: _*)

  lazy val socks5ProxyParams: Option[Socks5ProxyParams] =
    torConf.socks5ProxyParams

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

  lazy val inputRegType: InputRegistrationType = {
    val int = config.getInt(s"$moduleName.inputRegType")
    InputRegistrationType.fromInt(int)
  }

  lazy val inputScriptType: ScriptType = {
    val str = config.getString(s"$moduleName.inputScriptType")
    ScriptType.fromString(str)
  }

  lazy val changeScriptType: ScriptType = {
    val str = config.getString(s"$moduleName.changeScriptType")
    ScriptType.fromString(str)
  }

  lazy val mixScriptType: ScriptType = {
    val str = config.getString(s"$moduleName.mixScriptType")
    ScriptType.fromString(str)
  }

  lazy val remixPeers: Int = {
    config.getInt(s"$moduleName.remixPeers")
  }

  lazy val newPeers: Int = {
    config.getInt(s"$moduleName.newPeers")
  }

  lazy val remixAmount: Satoshis = {
    val long = config.getLong(s"$moduleName.remixAmount")
    Satoshis(long)
  }

  lazy val mixAmount: Satoshis = {
    val long = config.getLong(s"$moduleName.mixAmount")
    Satoshis(long)
  }

  lazy val mixFee: Satoshis = {
    val long = config.getLong(s"$moduleName.mixFee")
    Satoshis(long)
  }

  lazy val mixInterval: FiniteDuration = {
    val dur = config.getDuration(s"$moduleName.mixInterval")
    FiniteDuration(dur.getSeconds, SECONDS)
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
  override val moduleName: String = "vortex"

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".ln-vortex")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): VortexCoordinatorAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): VortexCoordinatorAppConfig =
    VortexCoordinatorAppConfig(datadir, confs: _*)
}
