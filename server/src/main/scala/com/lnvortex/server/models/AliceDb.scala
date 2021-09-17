package com.lnvortex.server.models

import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.crypto._

case class AliceDb(
    peerId: Sha256Digest,
    roundId: Sha256Digest,
    purpose: HDPurpose,
    coin: HDCoinType,
    accountIdx: Int,
    chain: HDChainType,
    nonceIndex: Int,
    nonce: SchnorrNonce,
    blindedOutputOpt: Option[FieldElement],
    changeOutputOpt: Option[TransactionOutput],
    blindOutputSigOpt: Option[FieldElement]
) {

  // todo make hardened path
  val noncePath: HDPath = {
    val coin = HDCoin(purpose, this.coin)
    val account = HDAccount(coin, accountIdx)
    val chain = HDChain(this.chain, account)
    HDAddress(chain, nonceIndex).toPath
  }
}

object AliceDbs {

  def newAlice(
      peerId: Sha256Digest,
      roundId: Sha256Digest,
      noncePath: BIP32Path,
      nonce: SchnorrNonce): AliceDb = {
    require(noncePath.size == 5,
            s"nonce path must have a size of 5, got ${noncePath.size}")
    val purpose = noncePath.path.head
    val _ :+ coin :+ account :+ chain :+ address = noncePath.path

    AliceDb(
      peerId = peerId,
      roundId = roundId,
      purpose = HDPurpose(purpose.index),
      coin = HDCoinType.fromInt(coin.index),
      accountIdx = account.index,
      chain = HDChainType.fromInt(chain.index),
      nonceIndex = address.index,
      nonce = nonce,
      blindedOutputOpt = None,
      changeOutputOpt = None,
      blindOutputSigOpt = None
    )
  }
}
