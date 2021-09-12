package com.lnvortex.client

import com.lnvortex.core.crypto.BlindingTweaks
import org.bitcoins.core.protocol.transaction._

case class InitDetails(
    inputs: Vector[OutputReference],
    changeOutput: TransactionOutput,
    mixOutput: TransactionOutput,
    tweaks: BlindingTweaks)
