package com.lnvortex.server.models

import org.bitcoins.core.protocol.transaction.TransactionOutPoint

import java.time.Instant

case class BannedUtxoDb(
    outPoint: TransactionOutPoint,
    bannedUntil: Instant,
    reason: String
)
