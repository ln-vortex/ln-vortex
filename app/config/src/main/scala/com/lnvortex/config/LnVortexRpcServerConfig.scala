package com.lnvortex.config

import com.lnvortex.core.VortexUtils
import com.lnvortex.core.VortexUtils.CONFIG_FILE_NAME
import com.typesafe.config.Config
import org.bitcoins.commons.config._
import org.bitcoins.crypto.CryptoUtil

import java.nio.file.{Files, Path, Paths}
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

  lazy val rpcUsernameOpt: Option[String] =
    config.getStringOrNone(s"$moduleName.rpcUser")

  lazy val rpcPasswordOpt: Option[String] =
    config.getStringOrNone(s"$moduleName.rpcPassword")

  private lazy val rpcCookieFile: Path = {
    config.getStringOrNone(s"$moduleName.rpcCookieFile") match {
      case Some(cookiePath) => Paths.get(cookiePath)
      case None => datadir.resolve(".rpc.cookie")
    }
  }

  lazy val rpcBind: Option[String] =
    config.getStringOrNone(s"$moduleName.rpcBind")

  lazy val rpcPort: Int =
    config.getIntOrElse(s"$moduleName.rpcPort",
                        VortexUtils.getDefaultClientRpcPort(network))

  lazy val rpcCreds: Vector[(String, String)] = {
    val configured = for {
      username <- rpcUsernameOpt
      password <- rpcPasswordOpt
    } yield (username, password)

    getCookie +: configured.toVector
  }

  def getCookie: (String, String) = {
    if (Files.exists(rpcCookieFile)) {
      val cookie = Files.readAllLines(rpcCookieFile).get(0)
      val split = cookie.split(":")
      (split(0), split(1))
    } else {
      createCookieFile()
    }
  }

  def createCookieFile(): (String, String) = {
    val user = "__COOKIE__"
    val password = CryptoUtil.randomBytes(32).toHex
    val cookie = s"$user:$password"

    Files.createDirectories(rpcCookieFile.getParent)
    Files.write(rpcCookieFile, cookie.getBytes())

    (user, password)
  }
}

object LnVortexRpcServerConfig
    extends AppConfigFactory[LnVortexRpcServerConfig] {

  override val moduleName: String = "vortex"

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
