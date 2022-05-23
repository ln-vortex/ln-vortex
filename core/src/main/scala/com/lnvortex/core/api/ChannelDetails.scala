package com.lnvortex.core.api

import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.core.protocol.ln.node.NodeId

case class ChannelDetails(
    alias: String,
    remotePubkey: NodeId,
    shortChannelId: ShortChannelId,
    public: Boolean,
    amount: CurrencyUnit,
    active: Boolean
)
