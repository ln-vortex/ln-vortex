package com.lnvortex.rpc

import akka.actor.ActorSystem
import com.bitcoins.clightning.rpc.CLightningRpcClient
import com.bitcoins.clightning.rpc.config._
import com.lnvortex.client.VortexClient
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.clightning._
import com.lnvortex.config.LnVortexRpcServerConfig
import com.lnvortex.core.api.VortexWalletApi
import com.lnvortex.lnd._
import com.lnvortex.rpc.LightningImplementation._
import com.typesafe.config.Config
import org.bitcoins.commons.config._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config._

import java.io.File
import java.nio.file.{Path, Paths}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Properties

case class LnVortexAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit
    val system: ActorSystem)
    extends AppConfig {

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

  private lazy val lndDataDir: Path =
    Paths.get(config.getString(s"$moduleName.lnd.datadir"))

  private lazy val lndBinary: File =
    Paths.get(config.getString(s"$moduleName.lnd.binary")).toFile

  private lazy val lndInstance: LndInstance =
    LndInstanceLocal.fromDataDir(lndDataDir.toFile)

  lazy val lndRpcClient: LndRpcClient =
    LndRpcClient(lndInstance, Some(lndBinary))

  private lazy val clightningDataDir: Path =
    Paths.get(config.getString(s"$moduleName.clightning.datadir"))

  private lazy val clightningBinary: File =
    Paths.get(config.getString(s"$moduleName.clightning.binary")).toFile

  private lazy val clightningInstance: CLightningInstanceLocal =
    CLightningInstanceLocal.fromDataDir(clightningDataDir.toFile)

  lazy val clightningClient: CLightningRpcClient =
    new CLightningRpcClient(clightningInstance, clightningBinary)

  lazy val wallet: VortexWalletApi = lightningImplementation match {
    case LND        => LndVortexWallet(lndRpcClient)
    case CLightning => CLightningVortexWallet(clightningClient)
  }

  lazy val client: VortexClient[VortexWalletApi] = VortexClient(wallet)
}

object LnVortexAppConfig
    extends AppConfigFactoryBase[LnVortexAppConfig, ActorSystem] {

  override val moduleName: String = "ln-vortex"

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
