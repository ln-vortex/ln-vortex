package com.lnvortex.core

import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction._

case class UnspentCoin(
    address: BitcoinAddress,
    amount: CurrencyUnit,
    outPoint: TransactionOutPoint,
    confirmed: Boolean,
    anonSet: Int,
    warning: Option[UTXOWarning],
    isChange: Boolean) {

  val spk: ScriptPubKey = address.scriptPubKey

  val output: TransactionOutput =
    TransactionOutput(amount, address.scriptPubKey)

  val outputReference: OutputReference = OutputReference(outPoint, output)
}
