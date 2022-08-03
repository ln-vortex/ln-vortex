package com.lnvortex.core

import com.lnvortex.core.crypto.BlindingTweaks
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.{
  OutputReference,
  TransactionOutput
}
import scodec.bits.ByteVector

import java.net.InetSocketAddress

case class InitDetails(
    inputs: Vector[OutputReference],
    addressOpt: Option[BitcoinAddress],
    nodeIdOpt: Option[NodeId],
    peerAddrOpt: Option[InetSocketAddress],
    changeSpkOpt: Option[ScriptPubKey],
    chanId: ByteVector,
    targetOutput: TransactionOutput,
    tweaks: BlindingTweaks) {
  val inputAmt: CurrencyUnit = inputs.map(_.output.value).sum
}
