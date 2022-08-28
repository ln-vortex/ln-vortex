package com.lnvortex.client.config

import akka.actor.ActorSystem
import com.lnvortex.client.db.UTXODAO
import com.lnvortex.core.api.CoordinatorAddress
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import org.bitcoins.commons.config._
import org.bitcoins.core.config._
import org.bitcoins.core.util._
import org.bitcoins.db._
import org.bitcoins.tor.config.TorAppConfig
import org.bitcoins.tor._

import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.jdk.CollectionConverters._
import scala.util.Properties

/** Configuration for Ln Vortex
  *
  * @param baseDatadir The data directory of the wallet
  * @param configOverrides Optional sequence of configuration overrides
  */
case class VortexAppConfig(baseDatadir: Path, configOverrides: Vector[Config])(
    implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[VortexAppConfig]
    with DbManagement
    with Logging {
  import profile.api._
  import system.dispatcher

  override val moduleName: String = VortexAppConfig.moduleName
  override type ConfigType = VortexAppConfig

  override def newConfigOfType(configs: Vector[Config]): VortexAppConfig =
    VortexAppConfig(baseDatadir, configs)

  override def start(): Future[Unit] = FutureUtil.makeAsync { () =>
    logger.info(s"Initializing vortex app config")

    if (Files.notExists(datadir)) {
      Files.createDirectories(datadir)
    }

    val numMigrations = migrate().migrationsExecuted
    logger.debug(s"Applied $numMigrations")
  }

  override def stop(): Future[Unit] = Future.unit

  lazy val torConf: TorAppConfig =
    TorAppConfig(baseDatadir, None, configOverrides)

  lazy val socks5ProxyParams: Option[Socks5ProxyParams] = {
    torConf.socks5ProxyParams
  }

  lazy val coordinatorAddresses: Map[
    BitcoinNetwork,
    Vector[CoordinatorAddress]] = {
    val coordinators =
      config.getConfigList(s"$moduleName.coordinators").asScala.toList

    val list = for {
      coordinatorConfig <- coordinators
      name = coordinatorConfig.getString("name")
      networkStr = coordinatorConfig.getString("network")
      address = coordinatorConfig.getString("address")
    } yield CoordinatorAddress(
      name,
      BitcoinNetworks.fromString(networkStr),
      NetworkUtil.parseInetSocketAddress(address, 12523))

    list.toVector.groupBy(_.network)
  }

  override lazy val appConfig: VortexAppConfig = this

  override lazy val allTables: List[TableQuery[Table[_]]] = {
    val utxoTable: TableQuery[Table[_]] = UTXODAO()(dispatcher, this).table
    List(utxoTable)
  }
}

object VortexAppConfig
    extends AppConfigFactoryBase[VortexAppConfig, ActorSystem] {
  override val moduleName: String = "vortex"

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".ln-vortex")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): VortexAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): VortexAppConfig =
    VortexAppConfig(datadir, confs)
}
