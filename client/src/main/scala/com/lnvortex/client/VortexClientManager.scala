package com.lnvortex.client

import akka.actor.ActorSystem
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.client.db.UTXODAO
import com.lnvortex.core._
import com.lnvortex.core.api.{
  ChannelDetails,
  CoordinatorAddress,
  VortexWalletApi
}
import grizzled.slf4j.Logging
import org.bitcoins.core.util.StartStopAsync

import scala.concurrent._

class VortexClientManager[+T <: VortexWalletApi](val vortexWallet: T)(implicit
    val system: ActorSystem,
    val config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {

  implicit val ec: ExecutionContext = system.dispatcher

  lazy val utxoDAO: UTXODAO = UTXODAO()

  lazy val coordinators: Vector[CoordinatorAddress] = {
    config.coordinatorAddresses.filter(_.network == vortexWallet.network)
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
