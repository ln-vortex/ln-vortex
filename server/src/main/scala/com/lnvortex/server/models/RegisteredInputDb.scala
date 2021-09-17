package com.lnvortex.server.models

import com.lnvortex.core.InputReference
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.crypto._

case class RegisteredInputDb(
    outPoint: TransactionOutPoint,
    output: TransactionOutput,
    inputProof: ScriptWitness,
    indexOpt: Option[Int],
    roundId: Sha256Digest,
    peerId: Sha256Digest
)

object RegisteredInputDbs {

  def fromInputReference(
      inputRef: InputReference,
      roundId: Sha256Digest,
      peerId: Sha256Digest): RegisteredInputDb = {
    import inputRef._

    RegisteredInputDb(outPoint, output, inputProof, None, roundId, peerId)
  }
}
