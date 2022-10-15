package com.lnvortex.client

import akka.actor.ActorSystem
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.client.db.UTXODAO
import com.lnvortex.core._
import com.lnvortex.core.api._
import grizzled.slf4j.Logging
import org.bitcoins.core.config.BitcoinNetwork
import org.bitcoins.core.util.StartStopAsync
import org.scalastr.core.NostrFilter
import com.lnvortex.core.api.CoordinatorAddress._
import play.api.libs.json._

import scala.collection.mutable
import scala.concurrent._
import scala.util._

class VortexClientManager[+T <: VortexWalletApi](
    private[lnvortex] val vortexWallet: T,
    val extraCoordinators: mutable.Buffer[CoordinatorAddress] =
      mutable.Buffer.empty)(implicit
    val system: ActorSystem,
    val config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {

  lazy val network: BitcoinNetwork = vortexWallet.network

  implicit val ec: ExecutionContext = system.dispatcher

  lazy val utxoDAO: UTXODAO = UTXODAO()

  lazy val coordinators: Vector[CoordinatorAddress] = {
    val all = config.coordinatorAddresses ++ extraCoordinators
    all.filter(_.network == vortexWallet.network).distinctBy(_.onion)
  }

  lazy val clients: Vector[VortexClient[VortexWalletApi]] = {
    coordinators.map { addr =>
      new VortexClient(vortexWallet, addr)
    }
  }

  lazy val clientMap: Map[String, VortexClient[VortexWalletApi]] = {
    clients.map(client => client.coordinatorAddress.name -> client).toMap
  }

  def listCoins(): Future[Vector[UnspentCoin]] = {
    clients.head.listCoins()
  }

  def listChannels(): Future[Vector[ChannelDetails]] = {
    clients.head.listChannels()
  }

  def listTransactions(): Future[Vector[TransactionDetails]] = {
    clients.head.listTransactions()
  }

  override def start(): Future[Unit] = {
    val filter =
      NostrFilter(
        ids = None,
        authors = None,
        kinds = Some(Vector(VortexUtils.NOSTR_KIND)),
        `#e` = None,
        `#p` = None,
        since = None,
        until = None,
        limit = None
      )

    config.nostrSource
      .mapAsync(5) { msg =>
        if (msg.kind == VortexUtils.NOSTR_KIND) {
          Try(Json.parse(msg.content)) match {
            case Failure(err) =>
              err.printStackTrace()

              Future.unit
            case Success(json) =>
              json.validate[CoordinatorAddress] match {
                case JsError(errors) =>
                  logger.error(
                    s"Failed to parse coordinator address from nostr: ${errors
                        .mkString("\n")}")
                  Future.unit
                case JsSuccess(addr, _) =>
                  if (addr.network == network) {
                    logger.info(s"Adding coordinator $addr")
                    extraCoordinators.addOne(addr)
                  } else {
                    logger.debug(
                      s"Coordinator address $addr is not on the same network as the wallet")
                  }
                  Future.unit
              }
          }
        } else Future.unit
      }
      .run()

    for {
      // start nostr clients
      _ <- Future.sequence(config.nostrClients.map(_.start()))
      _ = logger.info("Requesting coordinators from nostr relays!")
      _ <- Future.sequence(config.nostrClients.map(_.subscribe(filter)))

      _ <- vortexWallet.start()
      coins <- vortexWallet.listCoins()
      _ <- utxoDAO.createMissing(coins)

      startFs = clients.map(_.start())
      _ <- Future.sequence(startFs)
    } yield ()
  }

  override def stop(): Future[Unit] = {
    val stopFs = clients.map(_.stop())
    Future.sequence(stopFs).map(_ => ())
  }
}

object VortexClientManager extends Logging {

  def apply[T <: VortexWalletApi](
      vortexWallet: T,
      extraCoordinators: Vector[CoordinatorAddress] = Vector.empty)(implicit
      system: ActorSystem,
      config: VortexAppConfig): VortexClientManager[T] = {
    new VortexClientManager(vortexWallet,
                            mutable.Buffer.from(extraCoordinators))
  }
}
