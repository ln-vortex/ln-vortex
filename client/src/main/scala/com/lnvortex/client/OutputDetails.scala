package com.lnvortex.client

import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.transaction.TransactionOutput
import scodec.bits.ByteVector

case class OutputDetails(
    id: ByteVector,
    amount: CurrencyUnit,
    address: BitcoinAddress) {

  val output: TransactionOutput =
    TransactionOutput(amount, address.scriptPubKey)
}
