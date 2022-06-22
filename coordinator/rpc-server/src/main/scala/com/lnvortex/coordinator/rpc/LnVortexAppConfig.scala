package com.lnvortex.coordinator.rpc

import akka.actor.ActorSystem
import com.lnvortex.coordinator.config.LnVortexRpcServerConfig
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import com.typesafe.config.Config
import org.bitcoins.commons.config._
import org.bitcoins.rpc.client.common._
import org.bitcoins.rpc.client.v16.BitcoindV16RpcClient
import org.bitcoins.rpc.client.v17.BitcoindV17RpcClient
import org.bitcoins.rpc.client.v18.BitcoindV18RpcClient
import org.bitcoins.rpc.client.v19.BitcoindV19RpcClient
import org.bitcoins.rpc.client.v20.BitcoindV20RpcClient
import org.bitcoins.rpc.client.v21.BitcoindV21RpcClient
import org.bitcoins.rpc.client.v22.BitcoindV22RpcClient
import org.bitcoins.rpc.client.v23.BitcoindV23RpcClient
import org.bitcoins.rpc.config.{BitcoindInstance, BitcoindRpcAppConfig}

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

  override def start(): Future[Unit] = coordinatorConfig.start()
  override def stop(): Future[Unit] = Future.unit

  implicit lazy val rpcConfig: LnVortexRpcServerConfig =
    LnVortexRpcServerConfig.fromDatadir(datadir, configOverrides)

  implicit lazy val coordinatorConfig: VortexCoordinatorAppConfig =
    VortexCoordinatorAppConfig.fromDatadir(datadir, configOverrides)

  implicit lazy val bitcoindConfig: BitcoindRpcAppConfig =
    BitcoindRpcAppConfig.fromDatadir(datadir, configOverrides)

  private lazy val instance: BitcoindInstance = bitcoindConfig.bitcoindInstance

  lazy val bitcoind: BitcoindRpcClient = bitcoindConfig.versionOpt match {
    case Some(version) =>
      version match {
        case BitcoindVersion.V16          => BitcoindV16RpcClient(instance)
        case BitcoindVersion.V17          => BitcoindV17RpcClient(instance)
        case BitcoindVersion.V18          => BitcoindV18RpcClient(instance)
        case BitcoindVersion.V19          => BitcoindV19RpcClient(instance)
        case BitcoindVersion.V20          => BitcoindV20RpcClient(instance)
        case BitcoindVersion.V21          => BitcoindV21RpcClient(instance)
        case BitcoindVersion.V22          => BitcoindV22RpcClient(instance)
        case BitcoindVersion.V23          => BitcoindV23RpcClient(instance)
        case BitcoindVersion.Experimental => BitcoindV18RpcClient(instance)
        case BitcoindVersion.Unknown      => BitcoindRpcClient(instance)
      }
    case None => BitcoindRpcClient(instance)
  }

  lazy val coordinator: VortexCoordinator = VortexCoordinator(bitcoind)

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
