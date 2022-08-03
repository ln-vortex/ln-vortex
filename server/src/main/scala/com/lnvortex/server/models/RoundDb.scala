package com.lnvortex.server.models

import com.lnvortex.core.RoundStatus
import com.lnvortex.core.RoundStatus._
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._

import java.time.Instant

case class RoundDb(
    roundId: DoubleSha256Digest,
    status: RoundStatus,
    roundTime: Instant,
    feeRate: SatoshisPerVirtualByte,
    coordinatorFee: CurrencyUnit,
    inputFee: CurrencyUnit,
    outputFee: CurrencyUnit,
    changeFee: CurrencyUnit,
    amount: CurrencyUnit,
    psbtOpt: Option[PSBT],
    transactionOpt: Option[Transaction],
    txIdOpt: Option[DoubleSha256DigestBE],
    profitOpt: Option[CurrencyUnit]
) {
  // verify correct txid
  transactionOpt match {
    case Some(tx) =>
      require(txIdOpt.contains(tx.txIdBE), "transaction must match txid")
    case None => ()
  }

  def completeRound(tx: Transaction, profit: CurrencyUnit): RoundDb = {
    copy(status = RoundStatus.Signed,
         transactionOpt = Some(tx),
         txIdOpt = Some(tx.txIdBE),
         profitOpt = Some(profit))
  }
}

object RoundDbs {

  def newRound(
      roundId: DoubleSha256Digest,
      roundTime: Instant,
      feeRate: SatoshisPerVirtualByte,
      coordinatorFee: CurrencyUnit,
      inputFee: CurrencyUnit,
      outputFee: CurrencyUnit,
      changeFee: CurrencyUnit,
      amount: CurrencyUnit
  ): RoundDb = {
    RoundDb(
      roundId = roundId,
      status = Pending,
      roundTime = roundTime,
      feeRate = feeRate,
      coordinatorFee = coordinatorFee,
      inputFee = inputFee,
      outputFee = outputFee,
      changeFee = changeFee,
      amount = amount,
      psbtOpt = None,
      transactionOpt = None,
      txIdOpt = None,
      profitOpt = None
    )
  }
}
