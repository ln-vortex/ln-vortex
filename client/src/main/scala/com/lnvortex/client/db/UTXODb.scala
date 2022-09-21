package com.lnvortex.client.db

import com.lnvortex.core.UTXOWarning
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.crypto.DoubleSha256DigestBE

case class UTXODb(
    outPoint: TransactionOutPoint,
    txId: DoubleSha256DigestBE,
    scriptPubKey: ScriptPubKey,
    anonSet: Int,
    warning: Option[UTXOWarning],
    isChange: Boolean,
    isVortex: Boolean
)
