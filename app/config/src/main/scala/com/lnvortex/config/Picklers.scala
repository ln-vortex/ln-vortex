package com.lnvortex.config

import com.lnvortex.core._
import org.bitcoins.core.protocol.ln.node._
import org.bitcoins.commons.serializers.Picklers._
import org.bitcoins.core.currency._
import upickle.default._

object Picklers {

  implicit val currencyUnit: ReadWriter[CurrencyUnit] = {
    readwriter[Long].bimap(_.satoshis.toLong, Satoshis(_))
  }

  implicit val unspentCoinRW: ReadWriter[UnspentCoin] = macroRW[UnspentCoin]

  implicit val nodeIdPickler: ReadWriter[NodeId] = {
    readwriter[String].bimap(_.hex, NodeId.fromHex)
  }
}
