package com.lnvortex.server.models

import com.lnvortex.core.RoundStatus
import com.lnvortex.core.RoundStatus.Pending
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.DoubleSha256Digest

import java.time.Instant

case class RoundDb(
    roundId: DoubleSha256Digest,
    status: RoundStatus,
    roundTime: Instant,
    feeRate: SatoshisPerVirtualByte,
    mixFee: CurrencyUnit,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    amount: CurrencyUnit,
    psbtOpt: Option[PSBT],
    transactionOpt: Option[Transaction],
    profitOpt: Option[CurrencyUnit]
)

object RoundDbs {

  def newRound(
      roundId: DoubleSha256Digest,
      roundTime: Instant,
      feeRate: SatoshisPerVirtualByte,
      mixFee: CurrencyUnit,
      inputFee: CurrencyUnit,
      outputFee: CurrencyUnit,
      amount: CurrencyUnit
  ): RoundDb = {
    RoundDb(
      roundId = roundId,
      status = Pending,
      roundTime = roundTime,
      feeRate = feeRate,
      mixFee = mixFee,
      inputFee = inputFee,
      outputFee = outputFee,
      amount = amount,
      psbtOpt = None,
      transactionOpt = None,
      profitOpt = None
    )
  }
}
