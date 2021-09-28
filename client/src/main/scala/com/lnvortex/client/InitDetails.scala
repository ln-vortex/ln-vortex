package com.lnvortex.client

import com.lnvortex.core.crypto.BlindingTweaks
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction._
import scodec.bits.ByteVector

case class InitDetails(
    inputs: Vector[OutputReference],
    changeSpk: ScriptPubKey,
    chanId: ByteVector,
    mixOutput: TransactionOutput,
    tweaks: BlindingTweaks) {
  val inputAmt: CurrencyUnit = inputs.map(_.output.value).sum
}
