package com.lnvortex.client.networking

import org.bitcoins.tor.Socks5ProxyParams

import java.net.InetSocketAddress

case class Peer(
    socket: InetSocketAddress,
    socks5ProxyParams: Option[Socks5ProxyParams]) {

  override def toString: String =
    s"Peer(${socket.getHostString}:${socket.getPort}, $socks5ProxyParams)"

}

object Peer {

  def fromSocket(
      socket: InetSocketAddress,
      socks5ProxyParams: Option[Socks5ProxyParams]): Peer = {
    Peer(socket, socks5ProxyParams = socks5ProxyParams)
  }
}
