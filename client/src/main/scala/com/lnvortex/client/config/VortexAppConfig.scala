package com.lnvortex.client.config

import akka.actor.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import com.lnvortex.client.db.UTXODAO
import com.lnvortex.core.VortexUtils
import com.lnvortex.core.VortexUtils.CONFIG_FILE_NAME
import com.lnvortex.core.api.CoordinatorAddress
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import org.bitcoins.commons.config._
import org.bitcoins.core.config._
import org.bitcoins.core.util._
import org.bitcoins.db._
import org.bitcoins.tor._
import org.bitcoins.tor.config.TorAppConfig
import org.scalastr.client.NostrClient
import org.scalastr.core.NostrEvent

import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.jdk.CollectionConverters._
import scala.util.Properties

/** Configuration for Vortex
  *
  * @param baseDatadir
  *   The data directory of the client
  * @param configOverrides
  *   Optional sequence of configuration overrides
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

  override def configFileName: String = CONFIG_FILE_NAME

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
    new TorAppConfig(baseDatadir, None, configOverrides) {
      override def configFileName: String = CONFIG_FILE_NAME
    }

  lazy val socks5ProxyParams: Option[Socks5ProxyParams] = {
    torConf.socks5ProxyParams
  }

  lazy val coordinatorAddresses: Vector[CoordinatorAddress] = {
    val coordinators =
      config.getConfigList(s"$moduleName.coordinators").asScala.toList

    val list = for {
      coordinatorConfig <- coordinators
      name = coordinatorConfig.getString("name")
      networkStr = coordinatorConfig.getString("network")
      onion = coordinatorConfig.getString("onion")
    } yield {
      val network = BitcoinNetworks.fromString(networkStr)
      val addr =
        NetworkUtil.parseInetSocketAddress(onion,
                                           VortexUtils.getDefaultPort(network))
      CoordinatorAddress(name = name, network = network, onion = addr)
    }

    list.toVector
  }

  lazy val nostrRelays: Vector[String] = {
    config.getStringList(s"$moduleName.nostr.relays").asScala.toVector
  }

  lazy val (nostrQueue, nostrSource) = Source
    .queue[NostrEvent](bufferSize = 1_000,
                       OverflowStrategy.backpressure,
                       maxConcurrentOffers = 2)
    .toMat(BroadcastHub.sink)(Keep.both)
    .run()

  lazy val nostrClients: Vector[NostrClient] = {
    nostrRelays.map { relay =>
      new NostrClient(relay, torConf.socks5ProxyParams) {

        override def processEvent(
            subscriptionId: String,
            event: NostrEvent): Future[Unit] = {
          nostrQueue.offer(event).map(_ => ())(system.dispatcher)
        }

        override def processNotice(notice: String): Future[Unit] = Future.unit
      }
    }
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
