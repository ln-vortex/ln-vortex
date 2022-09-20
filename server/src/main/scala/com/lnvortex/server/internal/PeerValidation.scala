package com.lnvortex.server.internal

import com.lnvortex.core._
import com.lnvortex.server.VortexServerException
import com.lnvortex.server.VortexServerException._
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.models._
import grizzled.slf4j.Logging
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.util.TimeUtil

import scala.concurrent._
import scala.util._

trait PeerValidation extends Logging { self: VortexCoordinator =>

  def validateAliceInput(
      inputRef: InputReference,
      isRemix: Boolean,
      aliceDb: Option[AliceDb],
      otherInputs: Vector[RegisteredInputDb]): Future[
    Option[InvalidInputsException]] = {
    val outPoint = inputRef.outPoint
    val output = inputRef.output

    val correctScriptType =
      inputRef.output.scriptPubKey.scriptType == config.inputScriptType

    for {
      banDbOpt <- bannedUtxoDAO.read(outPoint)
      notBanned = banDbOpt match {
        case Some(banDb) =>
          TimeUtil.now.isAfter(banDb.bannedUntil)
        case None => true
      }

      txResult <- bitcoind.getRawTransaction(outPoint.txIdBE)
      txOutT = Try(txResult.vout(outPoint.vout.toInt))
      isRealInput = txOutT match {
        case Failure(_) => false
        case Success(out) =>
          val spk = ScriptPubKey.fromAsmHex(out.scriptPubKey.hex)
          TransactionOutput(out.value, spk) == output
      }
      isConfirmed = txResult.confirmations.exists(_ > 0)
      validConfs = isConfirmed || isRemix

      peerNonce = aliceDb match {
        case Some(db) => db.nonce
        case None =>
          throw new IllegalArgumentException(s"No alice found")
      }
      validProof = InputReference.verifyInputProof(inputRef, peerNonce)

      uniqueSpk = !otherInputs
        .map(_.output.scriptPubKey)
        .contains(output.scriptPubKey)

    } yield {
      if (!correctScriptType) {
        Some(new InvalidInputsException(
          s"UTXO ${outPoint.toHumanReadableString} has invalid script type, got ${inputRef.output.scriptPubKey.scriptType}"))
      } else if (!notBanned) {
        Some(
          new InvalidInputsException(
            s"UTXO ${outPoint.toHumanReadableString} is currently banned"))
      } else if (!isRealInput) {
        Some(new InvalidInputsException(
          s"UTXO ${outPoint.toHumanReadableString} given does not exist on the blockchain"))
      } else if (!validProof) {
        Some(
          new InvalidInputsException(
            s"UTXO $outPoint ownership proof was incorrect"))
      } else if (!validConfs) {
        Some(
          new InvalidInputsException(
            s"UTXO $outPoint does not have enough confirmations"))
      } else if (!uniqueSpk) {
        val address =
          BitcoinAddress.fromScriptPubKey(output.scriptPubKey, config.network)

        Some(
          new InvalidInputsException(
            s"$address has already been registered as an input or output"))
      } else None
    }
  }

  def validateAliceChange(
      isRemix: Boolean,
      registerInputs: RegisterInputs,
      otherInputs: Vector[RegisteredInputDb]): Option[VortexServerException] = {
    if (isRemix) {
      // if it is a remix we don't care about the change
      None
    } else {
      val uniqueChangeSpk = registerInputs.changeSpkOpt.forall { spk =>
        !otherInputs
          .map(_.output.scriptPubKey)
          .contains(spk)
      }

      // if change make sure it is of correct type
      val validChange = registerInputs.changeSpkOpt.forall(
        _.scriptType == config.changeScriptType) && uniqueChangeSpk

      val inputAmt = registerInputs.inputs.map(_.output.value).sum
      val changeE = FeeCalculator.calculateChangeOutput(
        roundParams = roundParams,
        isRemix = isRemix,
        numInputs = registerInputs.inputs.size,
        numRemixes = 0,
        numNewEntrants = 1,
        inputAmount = inputAmt,
        changeSpkOpt = registerInputs.changeSpkOpt
      )
      val excess = changeE match {
        case Left(amt)     => amt
        case Right(output) => output.value
      }

      val enoughFunding = excess >= Satoshis.zero

      lazy val changeSpkVec = registerInputs.changeSpkOpt match {
        case Some(spk) => Vector(spk)
        case None      => Vector.empty
      }
      lazy val allSpks =
        registerInputs.inputs.map(_.output.scriptPubKey) ++ changeSpkVec

      lazy val uniqueSpks = allSpks.size == allSpks.distinct.size

      if (!validChange) {
        Some(new InvalidChangeScriptPubKeyException(
          s"Alice registered with invalid change spk ${registerInputs.changeSpkOpt}"))
      } else if (!enoughFunding) {
        Some(
          new NotEnoughFundingException(
            s"Alice registered with not enough funding, need $excess more"))
      } else if (!uniqueSpks) {
        Some(
          new AttemptedAddressReuseException(
            s"Cannot have duplicate spks, got $allSpks"))
      } else None
    }
  }
}
