package com.lnvortex.core

import com.lnvortex.core.crypto.BlindingTweaks
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class RoundDetailsTest extends BitcoinSUnitTest {

  val roundAmount: Satoshis = Satoshis(200000)

  val roundParams: RoundParameters = RoundParameters(
    version = 0,
    roundId = DoubleSha256Digest.empty,
    amount = roundAmount,
    coordinatorFee = Satoshis.zero,
    publicKey = ECPublicKey.freshPublicKey.schnorrPublicKey,
    time = TimeUtil.currentEpochSecond,
    inputType = ScriptType.WITNESS_V0_KEYHASH,
    outputType = ScriptType.WITNESS_V0_KEYHASH,
    changeType = ScriptType.WITNESS_V0_KEYHASH,
    minPeers = 3,
    maxPeers = 5,
    status = "hello world",
    title = None,
    SatoshisPerVirtualByte.one
  )

  def testInitDetails(
      inputAmounts: Vector[Satoshis],
      hasChange: Boolean): InitDetails = {
    val tweaks = BlindingTweaks.freshBlindingTweaks(
      roundParams.publicKey,
      roundParams.publicKey.publicKey.schnorrNonce)

    val changeOpt =
      if (hasChange) Some(EmptyScriptPubKey)
      else None

    val inputs = inputAmounts.map { amt =>
      val output = TransactionOutput(amt, EmptyScriptPubKey)
      OutputReference(EmptyTransactionOutPoint, output)
    }

    InitDetails(
      inputs = inputs,
      addressOpt = None,
      nodeIdOpt = None,
      peerAddrOpt = None,
      changeSpkOpt = changeOpt,
      chanId = Sha256Digest.empty.bytes,
      targetOutput = EmptyTransactionOutput,
      tweaks = tweaks
    )
  }

  it must "calculate expected change amount" in {
    val details = InputsRegistered(
      round = roundParams,
      inputFee = Satoshis(149),
      outputFee = Satoshis(43),
      changeOutputFee = Satoshis(43),
      nonce = ECPublicKey.freshPublicKey.schnorrNonce,
      initDetails = testInitDetails(inputAmounts =
                                      Vector(Satoshis(100000), roundAmount),
                                    hasChange = true)
    )

    val amt = details.expectedAmtBack(numRemixes = 1, numNewEntrants = 1)

    assert(amt == Satoshis(99424))
  }

  it must "calculate expected change amount with no change" in {
    val details = InputsRegistered(
      round = roundParams,
      inputFee = Satoshis(149),
      outputFee = Satoshis(43),
      changeOutputFee = Satoshis(43),
      nonce = ECPublicKey.freshPublicKey.schnorrNonce,
      initDetails = testInitDetails(inputAmounts =
                                      Vector(Satoshis(100000), roundAmount),
                                    hasChange = false)
    )

    val amt = details.expectedAmtBack(numRemixes = 1, numNewEntrants = 1)

    assert(amt == Satoshis.zero)
  }
}
