package com.lnvortex.client.db

import org.bitcoins.core.protocol.transaction.TransactionOutPoint

case class UTXODb(
    outPoint: TransactionOutPoint,
    anonSet: Int,
    isChange: Boolean)
