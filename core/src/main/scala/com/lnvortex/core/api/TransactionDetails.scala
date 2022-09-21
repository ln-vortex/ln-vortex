package com.lnvortex.core.api

import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.crypto.DoubleSha256DigestBE

case class TransactionDetails(
    txId: DoubleSha256DigestBE,
    tx: Transaction,
    numConfirmations: Int,
    blockHeight: Int,
    isVortex: Boolean,
    label: String
)
