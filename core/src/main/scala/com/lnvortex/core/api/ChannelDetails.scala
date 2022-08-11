package com.lnvortex.core.api

import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction.TransactionOutPoint

case class ChannelDetails(
    alias: String,
    outPoint: TransactionOutPoint,
    remotePubkey: NodeId,
    shortChannelId: ShortChannelId,
    public: Boolean,
    amount: CurrencyUnit,
    active: Boolean,
    anonSet: Int
)
