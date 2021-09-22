package com.lnvortex.server

import akka.actor._
import akka.event.LoggingReceive
import akka.io.{IO, Tcp}
import com.lnvortex.server
import com.lnvortex.server.coordinator.VortexCoordinator
import org.bitcoins.tor._

import java.io.IOException
import java.net.InetSocketAddress
import scala.concurrent.{Future, Promise}

class VortexServer(
    coordinator: VortexCoordinator,
    bindAddress: InetSocketAddress,
    boundAddress: Option[Promise[InetSocketAddress]],
    dataHandlerFactory: ServerDataHandler.Factory =
      ServerDataHandler.defaultFactory)
    extends Actor
    with ActorLogging {

  import context.system

  IO(Tcp) ! Tcp.Bind(self, bindAddress)

  var socket: ActorRef = _

  override def receive: Receive = LoggingReceive {
    case Tcp.Bound(localAddress) =>
      log.info(s"Bound at $localAddress")
      boundAddress.foreach(_.success(localAddress))
      socket = sender()

    case VortexServer.Disconnect =>
      socket ! Tcp.Unbind

    case c @ Tcp.CommandFailed(_: Tcp.Bind) =>
      val ex = c.cause.getOrElse(new IOException("Unknown Error"))
      log.error(s"Cannot bind $boundAddress", ex)
      throw ex

    case Tcp.Connected(remoteAddress, _) =>
      val connection = sender()
      log.info(s"Received a connection from $remoteAddress")
      val _ = context.actorOf(
        Props(
          new ServerConnectionHandler(coordinator,
                                      connection,
                                      dataHandlerFactory)))
  }

  override def postStop(): Unit = {
    super.postStop()
    socket ! Tcp.Unbind
  }

  override def aroundReceive(receive: Receive, msg: Any): Unit = try {
    super.aroundReceive(receive, msg)
  } catch {
    case t: Throwable =>
      boundAddress.foreach(_.tryFailure(t))
  }

}

object VortexServer {

  case object Disconnect

  def props(
      vortexCoordinator: VortexCoordinator,
      bindAddress: InetSocketAddress,
      boundAddress: Option[Promise[InetSocketAddress]] = None,
      dataHandlerFactory: server.ServerDataHandler.Factory): Props = Props(
    new VortexServer(vortexCoordinator,
                     bindAddress,
                     boundAddress,
                     dataHandlerFactory))

  def bind(
      vortexCoordinator: VortexCoordinator,
      bindAddress: InetSocketAddress,
      torParams: Option[TorParams],
      dataHandlerFactory: ServerDataHandler.Factory =
        ServerDataHandler.defaultFactory)(implicit
      system: ActorSystem): Future[(InetSocketAddress, ActorRef)] = {
    import system.dispatcher

    val promise = Promise[InetSocketAddress]()

    for {
      onionAddress <- torParams match {
        case Some(params) =>
          TorController
            .setUpHiddenService(
              params.controlAddress,
              params.authentication,
              params.privateKeyPath,
              bindAddress.getPort
            )
            .map(Some(_))
        case None => Future.successful(None)
      }
      actorRef = system.actorOf(
        props(vortexCoordinator,
              bindAddress,
              Some(promise),
              dataHandlerFactory))
      boundAddress <- promise.future
    } yield {
      val addr = onionAddress.getOrElse(boundAddress)

      (addr, actorRef)
    }
  }
}
