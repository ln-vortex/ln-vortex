package com.lnvortex.client

import akka.actor.ActorSystem
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.client.db.UTXODAO
import com.lnvortex.core._
import com.lnvortex.core.api._
import grizzled.slf4j.Logging
import org.bitcoins.core.config.BitcoinNetwork
import org.bitcoins.core.util.{NetworkUtil, StartStopAsync}

import scala.concurrent._

class VortexClientManager[+T <: VortexWalletApi](
    private[lnvortex] val vortexWallet: T,
    extraCoordinators: Vector[CoordinatorAddress] = Vector.empty)(implicit
    val system: ActorSystem,
    val config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {

  lazy val network: BitcoinNetwork = vortexWallet.network

  implicit val ec: ExecutionContext = system.dispatcher

  lazy val utxoDAO: UTXODAO = UTXODAO()

  lazy val coordinators: Vector[CoordinatorAddress] = {
    val all = config.coordinatorAddresses ++ extraCoordinators
    val sameNetwork = all.filter(_.network == vortexWallet.network)

    // filter out tor-only coordinators if needed
    if (config.torConf.enabled) sameNetwork
    else {
      sameNetwork.filter { c =>
        c.clearnet.isDefined || NetworkUtil.isLoopbackAddress(c.onion)
      }
    }
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
    for {
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
