package com.lnvortex.server.models

import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.crypto._

case class AliceDb(
    peerId: Sha256Digest,
    roundId: DoubleSha256Digest,
    purpose: HDPurpose,
    coin: HDCoinType,
    accountIdx: Int,
    chain: HDChainType,
    nonceIndex: Int,
    nonce: SchnorrNonce,
    remixConfirmations: Option[Int],
    numInputs: Int,
    blindedOutputOpt: Option[FieldElement],
    changeSpkOpt: Option[ScriptPubKey],
    blindOutputSigOpt: Option[FieldElement],
    signed: Boolean
) {

  val noncePath: BIP32Path = {
    val coin = HDCoin(purpose, this.coin)
    val account = HDAccount(coin, accountIdx)
    val chain = HDChain(this.chain, account)
    val path = HDAddress(chain, nonceIndex).path
    // We need to make sure this is hardened for security
    val hardened = path.map(_.copy(hardened = true))
    BIP32Path(hardened)
  }

  def setRegisterInputValues(
      remixConfirmations: Option[Int],
      numInputs: Int,
      blindedOutput: FieldElement,
      changeSpkOpt: Option[ScriptPubKey],
      blindOutputSig: FieldElement): AliceDb = {
    copy(
      remixConfirmations = remixConfirmations,
      numInputs = numInputs,
      blindedOutputOpt = Some(blindedOutput),
      changeSpkOpt = changeSpkOpt,
      blindOutputSigOpt = Some(blindOutputSig)
    )
  }

  def markSigned(): AliceDb = {
    require(blindedOutputOpt.isDefined)
    require(changeSpkOpt.isDefined)
    require(blindOutputSigOpt.isDefined)

    copy(signed = true)
  }

  def unregister(): AliceDb = {
    copy(blindedOutputOpt = None,
         changeSpkOpt = None,
         blindOutputSigOpt = None,
         signed = false,
         numInputs = -1)
  }
}

object AliceDbs {

  def newAlice(
      peerId: Sha256Digest,
      roundId: DoubleSha256Digest,
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
      remixConfirmations = None,
      numInputs = -1,
      blindedOutputOpt = None,
      changeSpkOpt = None,
      blindOutputSigOpt = None,
      signed = false
    )
  }
}
