package com.lnvortex.core

import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.transaction._

case class UnspentCoin(
    address: BitcoinAddress,
    amount: CurrencyUnit,
    outPoint: TransactionOutPoint,
    confirmations: Long) {

  val output: TransactionOutput =
    TransactionOutput(amount, address.scriptPubKey)

  val outputReference: OutputReference = OutputReference(outPoint, output)
}
