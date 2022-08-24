package com.lnvortex.client

import akka.actor.ActorSystem
import com.lnvortex.client.config.{CoordinatorAddress, VortexAppConfig}
import com.lnvortex.client.db.UTXODAO
import com.lnvortex.core._
import com.lnvortex.core.api.{ChannelDetails, VortexWalletApi}
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

  lazy val coordinators: Vector[CoordinatorAddress] =
    config.coordinatorAddresses(vortexWallet.network)

  lazy val clients: Map[String, VortexClient[VortexWalletApi]] = {
    coordinators.map { addr =>
      addr.name -> new VortexClient(vortexWallet, addr)
    }.toMap
  }

  def listCoins(): Future[Vector[UnspentCoin]] = {
    clients.values.head.listCoins()
  }

  def listChannels(): Future[Vector[ChannelDetails]] = {
    clients.values.head.listChannels()
  }

  override def start(): Future[Unit] = {
    for {
      coins <- vortexWallet.listCoins()
      _ <- utxoDAO.createMissing(coins)

      startFs = clients.values.map(_.start())
      _ <- Future.sequence(startFs)
    } yield ()
  }

  override def stop(): Future[Unit] = {
    val stopFs = clients.values.map(_.stop())
    Future.sequence(stopFs).map(_ => ())
  }
}
