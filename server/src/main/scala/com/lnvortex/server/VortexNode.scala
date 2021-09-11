package com.lnvortex.server

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core._
import grizzled.slf4j.Logging
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.lnd.rpc.LndRpcClient

import java.net.InetSocketAddress
import scala.concurrent._

case class VortexNode(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem,
    config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {

  implicit val ec: ExecutionContextExecutor = system.dispatcher

  private[node] lazy val serverBindF: Future[(InetSocketAddress, ActorRef)] = {
    logger.info(
      s"Binding server to ${config.listenAddress}, with tor hidden service: ${config.torParams.isDefined}")

//    DLCServer
//      .bind(
//        wallet,
//        config.listenAddress,
//        config.torParams
//      )
//      .map { case (addr, actor) =>
//        hostAddressP.success(addr)
//        (addr, actor)
//      }
    null
  }

  private val hostAddressP: Promise[InetSocketAddress] =
    Promise[InetSocketAddress]()

  def getHostAddress: Future[InetSocketAddress] = {
    hostAddressP.future
  }

  override def start(): Future[Unit] = {
    serverBindF.map(_ => ())
  }

  override def stop(): Future[Unit] = {
    serverBindF.map { case (_, actorRef) =>
      system.stop(actorRef)
    }
  }
}
