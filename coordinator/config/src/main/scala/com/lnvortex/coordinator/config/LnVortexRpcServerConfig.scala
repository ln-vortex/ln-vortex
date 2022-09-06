package com.lnvortex.coordinator.config

import com.lnvortex.core.VortexUtils.CONFIG_FILE_NAME
import com.typesafe.config.Config
import org.bitcoins.commons.config._

import java.nio.file.{Path, Paths}
import scala.concurrent._
import scala.util.Properties

case class LnVortexRpcServerConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit
    val ec: ExecutionContext)
    extends AppConfig {

  override type ConfigType = LnVortexRpcServerConfig

  override def newConfigOfType(
      configs: Vector[Config]): LnVortexRpcServerConfig =
    LnVortexRpcServerConfig(directory, configs ++ configOverrides)

  override val moduleName: String = LnVortexRpcServerConfig.moduleName
  override val baseDatadir: Path = directory

  override lazy val datadir: Path = baseDatadir

  override def configFileName: String = CONFIG_FILE_NAME

  override def start(): Future[Unit] = Future.unit
  override def stop(): Future[Unit] = Future.unit

  lazy val rpcUsername: String =
    config.getString(s"$moduleName.rpcUser")

  lazy val rpcPassword: String =
    config.getString(s"$moduleName.rpcPassword")

  lazy val rpcBind: Option[String] =
    config.getStringOrNone(s"$moduleName.rpcBind")

  lazy val rpcPort: Int =
    config.getIntOrElse(s"$moduleName.rpcPort", 12522)
}

object LnVortexRpcServerConfig
    extends AppConfigFactory[LnVortexRpcServerConfig] {

  override val moduleName: String = "coordinator"

  final val DEFAULT_DATADIR: Path =
    Paths.get(Properties.userHome, ".ln-vortex")

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ExecutionContext): LnVortexRpcServerConfig =
    LnVortexRpcServerConfig(datadir, confs)

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ExecutionContext): LnVortexRpcServerConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }
}
