package com.lnvortex.client

import com.lnvortex.core.crypto.BlindingTweaks
import org.bitcoins.core.protocol.transaction._
import scodec.bits.ByteVector

case class InitDetails(
    inputs: Vector[OutputReference],
    changeOutput: TransactionOutput,
    chanId: ByteVector,
    mixOutput: TransactionOutput,
    tweaks: BlindingTweaks)
