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
    coordinatorFee: CurrencyUnit,
    amount: CurrencyUnit,
    feeRate: Option[SatoshisPerVirtualByte],
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
      coordinatorFee: CurrencyUnit,
      amount: CurrencyUnit
  ): RoundDb = {
    RoundDb(
      roundId = roundId,
      status = Pending,
      roundTime = roundTime,
      coordinatorFee = coordinatorFee,
      amount = amount,
      feeRate = None,
      psbtOpt = None,
      transactionOpt = None,
      txIdOpt = None,
      profitOpt = None
    )
  }
}
