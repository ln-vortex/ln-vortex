package com.lnvortex.server.models

import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.crypto._

case class RegisteredInputDb(
    outPoint: TransactionOutPoint,
    output: TransactionOutput,
    inputProof: ScriptWitness,
    roundId: Sha256Digest,
    peerId: Sha256Digest
)
