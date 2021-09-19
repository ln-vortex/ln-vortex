package com.lnvortex.server.models

import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.crypto.{DoubleSha256Digest, SchnorrDigitalSignature}

case class RegisteredOutputDb(
    output: TransactionOutput,
    sig: SchnorrDigitalSignature,
    roundId: DoubleSha256Digest
)
