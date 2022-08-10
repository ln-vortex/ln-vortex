package com.lnvortex.client.db

import com.lnvortex.core.UTXOWarning
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutPoint

case class UTXODb(
    outPoint: TransactionOutPoint,
    scriptPubKey: ScriptPubKey,
    anonSet: Int,
    warning: Option[UTXOWarning],
    isChange: Boolean)
