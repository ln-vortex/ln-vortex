package com.lnvortex.rpc

import akka.actor.ActorSystem
import com.bitcoins.clightning.rpc.CLightningRpcClient
import com.bitcoins.clightning.rpc.config._
import com.lnvortex.bitcoind.BitcoindVortexWallet
import com.lnvortex.client._
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.clightning._
import com.lnvortex.config.LnVortexRpcServerConfig
import com.lnvortex.core.VortexUtils.CONFIG_FILE_NAME
import com.lnvortex.core.api.VortexWalletApi
import com.lnvortex.lnd._
import com.lnvortex.rpc.LightningImplementation._
import com.typesafe.config.Config
import org.bitcoins.commons.config._
import org.bitcoins.commons.util.NativeProcessFactory
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config._
import org.bitcoins.rpc.client.common.{BitcoindRpcClient, BitcoindVersion}
import org.bitcoins.rpc.client.v18.BitcoindV18RpcClient
import org.bitcoins.rpc.client.v19.BitcoindV19RpcClient
import org.bitcoins.rpc.client.v20.BitcoindV20RpcClient
import org.bitcoins.rpc.client.v21.BitcoindV21RpcClient
import org.bitcoins.rpc.client.v22.BitcoindV22RpcClient
import org.bitcoins.rpc.client.v23.BitcoindV23RpcClient
import org.bitcoins.rpc.config.{BitcoindInstance, BitcoindRpcAppConfig}
import scodec.bits.ByteVector

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Properties, Try}

case class LnVortexAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit
    val system: ActorSystem)
    extends AppConfig {

  override def configFileName: String = CONFIG_FILE_NAME

  implicit val ec: ExecutionContext = system.dispatcher

  override type ConfigType = LnVortexAppConfig

  override def newConfigOfType(confs: Vector[Config]): LnVortexAppConfig =
    LnVortexAppConfig(directory, confs ++ configOverrides)

  override val moduleName: String = LnVortexAppConfig.moduleName
  override val baseDatadir: Path = directory

  override lazy val datadir: Path = baseDatadir

  override def start(): Future[Unit] = Future.unit
  override def stop(): Future[Unit] = Future.unit

  implicit lazy val rpcConfig: LnVortexRpcServerConfig =
    LnVortexRpcServerConfig.fromDatadir(datadir, configOverrides)

  implicit lazy val clientConfig: VortexAppConfig =
    VortexAppConfig.fromDatadir(datadir, configOverrides)

  lazy val lightningImplementation: LightningImplementation = {
    val str = config.getString(s"$moduleName.lightningImplementation")
    LightningImplementation.fromString(str)
  }

  private lazy val lndDataDir: Path = {
    config.getStringOrNone(s"$moduleName.lnd.datadir") match {
      case Some(str) => Paths.get(str.replace("~", Properties.userHome))
      case None      => LndInstanceLocal.DEFAULT_DATADIR
    }
  }

  private lazy val lndRpcUri: Option[URI] = {
    config.getStringOrNone(s"$moduleName.lnd.rpcUri").map { str =>
      if (str.startsWith("http") || str.startsWith("https")) {
        new URI(str)
      } else {
        new URI(s"http://$str")
      }
    }
  }

  private lazy val lndMacaroonOpt: Option[String] = {
    config.getStringOrNone(s"$moduleName.lnd.macaroonFile").map { pathStr =>
      val path = Paths.get(pathStr.replace("~", Properties.userHome))
      val bytes = Files.readAllBytes(path)

      ByteVector(bytes).toHex
    }
  }

  private lazy val lndTlsCertOpt: Option[File] = {
    config.getStringOrNone(s"$moduleName.lnd.tlsCert").map { pathStr =>
      val path = Paths.get(pathStr.replace("~", Properties.userHome))
      path.toFile
    }
  }

  private lazy val lndBinary: File = {
    config.getStringOrNone(s"$moduleName.lnd.binary") match {
      case Some(str) => new File(str.replace("~", Properties.userHome))
      case None =>
        NativeProcessFactory
          .findExecutableOnPath("lnd")
          .getOrElse(sys.error("Could not find lnd binary"))
    }
  }

  private lazy val lndInstance: LndInstance = {
    lndMacaroonOpt match {
      case Some(value) =>
        LndInstanceRemote(
          rpcUri = lndRpcUri.getOrElse(new URI("http://127.0.0.1:10009")),
          macaroon = value,
          certFileOpt = lndTlsCertOpt,
          certificateOpt = None)
      case None =>
        val dir = lndDataDir.toFile
        require(dir.exists, s"$lndDataDir does not exist!")
        require(dir.isDirectory, s"$lndDataDir is not a directory!")

        val confFile = lndDataDir.resolve("lnd.conf").toFile
        val config = LndConfig(confFile, dir)

        val remoteConfig = config.lndInstanceRemote

        lndRpcUri match {
          case Some(uri) => remoteConfig.copy(rpcUri = uri)
          case None      => remoteConfig
        }
    }
  }

  lazy val lndRpcClient: LndRpcClient =
    new LndRpcClient(lndInstance, Try(lndBinary).toOption)

  private lazy val clnDataDir: Path = {
    config.getStringOrNone(s"$moduleName.cln.datadir") match {
      case Some(str) => Paths.get(str.replace("~", Properties.userHome))
      case None      => Paths.get(Properties.userHome, ".lightning")
    }
  }

  private lazy val clnBinary: File = {
    config.getStringOrNone(s"$moduleName.cln.binary") match {
      case Some(str) => new File(str.replace("~", Properties.userHome))
      case None =>
        NativeProcessFactory
          .findExecutableOnPath("lightningd")
          .getOrElse(sys.error("Could not find lightningd binary"))
    }
  }

  private lazy val clnInstance: CLightningInstanceLocal =
    CLightningInstanceLocal.fromDataDir(clnDataDir.toFile)

  lazy val clnClient: CLightningRpcClient =
    new CLightningRpcClient(clnInstance, clnBinary)

  implicit lazy val bitcoindConfig: BitcoindRpcAppConfig =
    BitcoindRpcAppConfig.fromDatadir(datadir, configOverrides)

  private lazy val instance: BitcoindInstance = bitcoindConfig.bitcoindInstance

  lazy val bitcoind: BitcoindRpcClient = bitcoindConfig.versionOpt match {
    case Some(version) =>
      version match {
        case BitcoindVersion.V18     => BitcoindV18RpcClient(instance)
        case BitcoindVersion.V19     => BitcoindV19RpcClient(instance)
        case BitcoindVersion.V20     => BitcoindV20RpcClient(instance)
        case BitcoindVersion.V21     => BitcoindV21RpcClient(instance)
        case BitcoindVersion.V22     => BitcoindV22RpcClient(instance)
        case BitcoindVersion.V23     => BitcoindV23RpcClient(instance)
        case BitcoindVersion.Unknown => BitcoindRpcClient(instance)
      }
    case None => BitcoindRpcClient(instance)
  }

  lazy val wallet: VortexWalletApi = lightningImplementation match {
    case LND      => LndVortexWallet(lndRpcClient)
    case CLN      => CLightningVortexWallet(clnClient)
    case Bitcoind => BitcoindVortexWallet(bitcoind)
  }

  lazy val clientManager: VortexClientManager[VortexWalletApi] =
    new VortexClientManager(wallet)
}

object LnVortexAppConfig
    extends AppConfigFactoryBase[LnVortexAppConfig, ActorSystem] {

  override val moduleName: String = "vortex"

  final val DEFAULT_DATADIR: Path =
    Paths.get(Properties.userHome, ".ln-vortex")

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      system: ActorSystem): LnVortexAppConfig =
    LnVortexAppConfig(datadir, confs)

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      system: ActorSystem): LnVortexAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }
}
