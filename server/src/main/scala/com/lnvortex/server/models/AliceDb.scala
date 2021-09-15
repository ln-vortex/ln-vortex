package com.lnvortex.server.models

import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.crypto.{FieldElement, Sha256Digest}

case class AliceDb(
    peerId: Sha256Digest,
    roundId: Sha256Digest,
    purpose: HDPurpose,
    coin: HDCoinType,
    account: HDAccount,
    chain: HDChainType,
    nonceIndex: Int,
    blindedOutputOpt: Option[FieldElement],
    changeOutputOpt: Option[TransactionOutput],
    blindOutputSigOpt: Option[FieldElement]
)

object AliceDbs {

  def newAlice(
      peerId: Sha256Digest,
      roundId: Sha256Digest,
      noncePath: HDPath): AliceDb = {
    AliceDb(
      peerId = peerId,
      roundId = roundId,
      purpose = noncePath.purpose,
      coin = noncePath.coin.coinType,
      account = noncePath.account,
      chain = noncePath.chain.chainType,
      nonceIndex = noncePath.address.index,
      blindedOutputOpt = None,
      changeOutputOpt = None,
      blindOutputSigOpt = None
    )
  }
}
