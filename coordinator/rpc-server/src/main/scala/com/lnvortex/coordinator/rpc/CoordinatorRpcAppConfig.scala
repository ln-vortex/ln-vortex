package com.lnvortex.coordinator.rpc

import akka.actor.ActorSystem
import com.danielasfregola.twitter4s.TwitterRestClient
import com.danielasfregola.twitter4s.entities.{AccessToken, ConsumerToken}
import com.lnvortex.coordinator.config.LnVortexRpcServerConfig
import com.lnvortex.core.VortexUtils.CONFIG_FILE_NAME
import com.lnvortex.server.config._
import com.typesafe.config.Config
import org.bitcoins.commons.config._
import org.bitcoins.rpc.client.common._
import org.bitcoins.rpc.client.v19.BitcoindV19RpcClient
import org.bitcoins.rpc.client.v20.BitcoindV20RpcClient
import org.bitcoins.rpc.client.v21.BitcoindV21RpcClient
import org.bitcoins.rpc.client.v22.BitcoindV22RpcClient
import org.bitcoins.rpc.client.v23.BitcoindV23RpcClient
import org.bitcoins.rpc.config._

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Properties

case class CoordinatorRpcAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit
    val system: ActorSystem)
    extends AppConfig {

  implicit val ec: ExecutionContext = system.dispatcher

  override type ConfigType = CoordinatorRpcAppConfig

  override def newConfigOfType(confs: Vector[Config]): CoordinatorRpcAppConfig =
    CoordinatorRpcAppConfig(directory, confs ++ configOverrides)

  override val moduleName: String = CoordinatorRpcAppConfig.moduleName
  override val baseDatadir: Path = directory

  override lazy val datadir: Path = baseDatadir

  override def configFileName: String = CONFIG_FILE_NAME

  override def start(): Future[Unit] = {
    val callbacks = CoordinatorCallbacks(
      Vector(telegramCallback, twitterCallback),
      Vector.empty)
    coordinatorConfig.addCallbacks(callbacks)

    for {
      _ <- coordinatorConfig.start()
      _ <- rpcConfig.start()
      _ <- telegramHandlerOpt.map(_.start()).getOrElse(Future.unit)
    } yield ()
  }

  override def stop(): Future[Unit] = Future.unit

  private lazy val telegramCreds: Option[(String, String)] = {
    val envCredsOpt = Option(System.getenv("VORTEX_TELEGRAM_CREDS"))
    val envIdOpt = Option(System.getenv("VORTEX_TELEGRAM_ID"))

    for {
      creds <- envCredsOpt.orElse(
        config.getStringOrNone(s"$moduleName.telegramCreds"))

      id <- envIdOpt.orElse(config.getStringOrNone(s"$moduleName.telegramId"))

      res <-
        if (creds.isEmpty || id.isEmpty) None
        else Some((creds, id))
    } yield res
  }

  lazy val telegramHandlerOpt: Option[TelegramHandler] = telegramCreds.map {
    case (creds, id) => new TelegramHandler(creds, id)(this, system)
  }

  private val telegramCallback: OnRoundComplete = { roundDb =>
    // don't block
    val _ = telegramHandlerOpt.map { telegram =>
      system.scheduler.scheduleOnce(15.seconds) {
        telegram.roundSuccessMessage(roundDb)
        ()
      }
    }
    Future.unit
  }

  lazy val twitterHandlerOpt: Option[TwitterHandler] = {
    for {
      consumerKey <- config.getStringOrNone(s"$moduleName.twitter.consumer.key")
      consumerSecret <- config.getStringOrNone(
        s"$moduleName.twitter.consumer.secret")

      accessKey <- config.getStringOrNone(s"$moduleName.twitter.access.key")
      accessSecret <- config.getStringOrNone(
        s"$moduleName.twitter.access.secret")

      consumerToken <-
        if (consumerKey.isEmpty || consumerSecret.isEmpty) None
        else Some(ConsumerToken(consumerKey, consumerSecret))

      accessToken <-
        if (accessKey.isEmpty || accessSecret.isEmpty) None
        else Some(AccessToken(accessKey, accessSecret))

      client = TwitterRestClient.withActorSystem(consumerToken, accessToken)(
        system)
    } yield new TwitterHandler(client)(this)
  }

  private val twitterCallback: OnRoundComplete = { roundDb =>
    // don't block
    val _ = twitterHandlerOpt.map { twitter =>
      system.scheduler.scheduleOnce(15.seconds) {
        twitter.roundSuccessTweet(roundDb)
        ()
      }
    }
    Future.unit
  }

  implicit lazy val rpcConfig: LnVortexRpcServerConfig =
    LnVortexRpcServerConfig.fromDatadir(datadir, configOverrides)

  implicit lazy val coordinatorConfig: VortexCoordinatorAppConfig =
    VortexCoordinatorAppConfig.fromDatadir(datadir, configOverrides)

  implicit lazy val bitcoindConfig: BitcoindRpcAppConfig =
    new BitcoindRpcAppConfig(datadir, configOverrides) {
      override def configFileName: String = CONFIG_FILE_NAME
    }

  private lazy val instance: BitcoindInstance = bitcoindConfig.bitcoindInstance

  lazy val bitcoind: BitcoindRpcClient = bitcoindConfig.versionOpt match {
    case Some(version) =>
      version match {
        case BitcoindVersion.V19     => BitcoindV19RpcClient(instance)
        case BitcoindVersion.V20     => BitcoindV20RpcClient(instance)
        case BitcoindVersion.V21     => BitcoindV21RpcClient(instance)
        case BitcoindVersion.V22     => BitcoindV22RpcClient(instance)
        case BitcoindVersion.V23     => BitcoindV23RpcClient(instance)
        case BitcoindVersion.Unknown => BitcoindRpcClient(instance)
      }
    case None => BitcoindRpcClient(instance)
  }

}

object CoordinatorRpcAppConfig
    extends AppConfigFactoryBase[CoordinatorRpcAppConfig, ActorSystem] {

  override val moduleName: String = "vortex"

  final val DEFAULT_DATADIR: Path =
    Paths.get(Properties.userHome, ".ln-vortex")

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      system: ActorSystem): CoordinatorRpcAppConfig =
    CoordinatorRpcAppConfig(datadir, confs)

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      system: ActorSystem): CoordinatorRpcAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }
}
