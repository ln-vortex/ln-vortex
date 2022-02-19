package com.lnvortex.config

import com.lnvortex.core._
import org.bitcoins.core.protocol.ln.node._
import com.lnvortex.core.api._
import org.bitcoins.commons.serializers.Picklers._
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import upickle.default._

object Picklers {

  implicit val currencyUnitRW: ReadWriter[CurrencyUnit] = {
    readwriter[Long].bimap(_.satoshis.toLong, Satoshis(_))
  }

  implicit val ShortChannelIdRW: ReadWriter[ShortChannelId] = {
    readwriter[String].bimap(_.toHumanReadableString,
                             ShortChannelId.fromHumanReadableString)
  }

  implicit val unspentCoinRW: ReadWriter[UnspentCoin] = macroRW[UnspentCoin]

  implicit val TransactionDetailsRW: ReadWriter[TransactionDetails] =
    macroRW[TransactionDetails]

  implicit val ChannelDetailsRW: ReadWriter[ChannelDetails] =
    macroRW[ChannelDetails]

  implicit val nodeIdPickler: ReadWriter[NodeId] = {
    readwriter[String].bimap(_.hex, NodeId.fromHex)
  }
}
