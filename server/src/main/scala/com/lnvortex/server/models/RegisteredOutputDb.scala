package com.lnvortex.server.models

import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.crypto.{SchnorrDigitalSignature, Sha256Digest}

case class RegisteredOutputDb(
    output: TransactionOutput,
    sig: SchnorrDigitalSignature,
    roundId: Sha256Digest
)
