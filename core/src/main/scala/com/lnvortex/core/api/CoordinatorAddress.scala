package com.lnvortex.core.api

import org.bitcoins.core.config._

import java.net.InetSocketAddress

case class CoordinatorAddress(
    name: String,
    network: BitcoinNetwork,
    onion: InetSocketAddress)

object CoordinatorAddress {

  lazy val dummy: CoordinatorAddress = CoordinatorAddress(
    "dummy",
    RegTest,
    new InetSocketAddress("localhost", 12523))
}
