package com.lnvortex.server.models

import com.lnvortex.core.RoundStatus
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.Sha256Digest

import java.time.Instant

case class RoundDb(
    roundId: Sha256Digest,
    status: RoundStatus,
    roundTime: Instant,
    feeRate: SatoshisPerVirtualByte,
    amount: CurrencyUnit,
    psbtOpt: Option[PSBT],
    transactionOpt: Option[Transaction],
    profitOpt: Option[CurrencyUnit]
)
