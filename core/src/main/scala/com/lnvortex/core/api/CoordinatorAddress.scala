package com.lnvortex.core.api

import org.bitcoins.core.config._
import play.api.libs.json._

import java.net.{InetSocketAddress, URI}

case class CoordinatorAddress(
    name: String,
    network: BitcoinNetwork,
    onion: InetSocketAddress)

object CoordinatorAddress {

  implicit val CoordinatorAddressReads: Reads[CoordinatorAddress] = Reads {
    case obj: JsObject =>
      for {
        name <- (obj \ "name").validate[String]
        network <- (obj \ "network").validate[String]
        onion <- (obj \ "onion").validate[String]
      } yield {
        val uri = new URI("tcp://" + onion)
        val inet = InetSocketAddress.createUnresolved(uri.getHost, uri.getPort)

        CoordinatorAddress(name, BitcoinNetworks.fromString(network), inet)
      }
    case _ => JsError("CoordinatorAddress must be a json object")
  }

  implicit val CoordinatorAddressWrites: OWrites[CoordinatorAddress] = OWrites {
    address =>
      Json.obj(
        "name" -> address.name,
        "network" -> address.network.toString,
        "onion" -> s"${address.onion.getHostString}:${address.onion.getPort}"
      )
  }

  lazy val dummy: CoordinatorAddress = CoordinatorAddress(
    "dummy",
    RegTest,
    new InetSocketAddress("localhost", 12523))
}
