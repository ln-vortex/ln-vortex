package com.lnvortex.core.gen

import com.lnvortex.core._
import org.bitcoins.core.protocol.script.EmptyScriptPubKey
import org.bitcoins.core.protocol.transaction.OutputReference
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.testkitcore.gen._
import org.scalacheck.Gen

object Generators {

  def warning: Gen[UTXOWarning] = Gen.oneOf(UTXOWarning.all)

  def unspentCoin: Gen[UnspentCoin] = {
    for {
      addr <- AddressGenerator.bitcoinAddress
      amt <- CurrencyUnitGenerator.positiveSatoshis
      outpoint <- TransactionGenerators.outPoint
      confirmed <- NumberGenerator.bool
      anonSet <- NumberGenerator.positiveInts
      warningOpt <- Gen.option(warning)
      change <- NumberGenerator.bool
    } yield {
      UnspentCoin(address = addr,
                  amount = amt,
                  outPoint = outpoint,
                  confirmed = confirmed,
                  anonSet = anonSet,
                  warning = warningOpt,
                  isChange = change)
    }
  }

  def validScriptType: Gen[ScriptType] = {
    Gen.oneOf(ScriptType.WITNESS_V0_KEYHASH,
              ScriptType.WITNESS_V0_SCRIPTHASH,
              ScriptType.WITNESS_V1_TAPROOT)
  }

  def roundParameters: Gen[RoundParameters] = {
    for {
      version <- NumberGenerator.uInt16.map(_.toInt)
      roundId <- CryptoGenerators.doubleSha256Digest
      amount <- CurrencyUnitGenerator.positiveRealistic
      coordinatorFee <- CurrencyUnitGenerator.positiveRealistic
      pubkey <- CryptoGenerators.schnorrPublicKey
      time = TimeUtil.currentEpochSecond
      inputType <- validScriptType
      outputType <- validScriptType
      changeType <- validScriptType
      maxPeers <- NumberGenerator.uInt16.map(_.toInt)
      status <- StringGenerators.genString
    } yield {
      RoundParameters(
        version = version,
        roundId = roundId,
        amount = amount,
        coordinatorFee = coordinatorFee,
        publicKey = pubkey,
        time = time,
        inputType = inputType,
        outputType = outputType,
        changeType = changeType,
        maxPeers = maxPeers,
        status = status
      )
    }
  }

  def nonceMsg: Gen[NonceMessage] = {
    for {
      nonce <- CryptoGenerators.schnorrNonce
    } yield NonceMessage(nonce)
  }

  def askInputs: Gen[AskInputs] = {
    for {
      roundId <- CryptoGenerators.doubleSha256Digest
      inFee <- CurrencyUnitGenerator.positiveSatoshis
      outFee <- CurrencyUnitGenerator.positiveSatoshis
      changeOutputFee <- CurrencyUnitGenerator.positiveSatoshis
    } yield AskInputs(roundId, inFee, outFee, changeOutputFee)
  }

  def inputReference: Gen[InputReference] = {
    for {
      outpoint <- TransactionGenerators.outPoint
      spk <- ScriptGenerators.p2wpkhSPKV0.map(_._1)
      output <- TransactionGenerators.outputTo(spk)
      scriptWit <- WitnessGenerators.scriptWitness
    } yield InputReference(OutputReference(outpoint, output), scriptWit)
  }

  def registerInputs: Gen[RegisterInputs] = {
    for {
      numInputs <- Gen.choose(1, 7)
      inputs <- Gen.listOfN(numInputs, inputReference)
      blindedOutput <- CryptoGenerators.fieldElement
      changeSpkOpt <- Gen.option {
        ScriptGenerators.scriptPubKey
          .map(_._1)
          .suchThat(_ != EmptyScriptPubKey)
      }
    } yield RegisterInputs(inputs.toVector, blindedOutput, changeSpkOpt)
  }

  def blindedSig: Gen[BlindedSig] = {
    for {
      blindOutputSig <- CryptoGenerators.fieldElement
    } yield BlindedSig(blindOutputSig)
  }

  def registerOutput: Gen[RegisterOutput] = {
    for {
      sig <- CryptoGenerators.schnorrDigitalSignature
      output <- TransactionGenerators.output
    } yield RegisterOutput(sig, output)
  }

  def unsignedPsbtMessage: Gen[UnsignedPsbtMessage] = {
    for {
      psbt <- PSBTGenerators.arbitraryPSBT
    } yield UnsignedPsbtMessage(psbt)
  }

  def signedPsbtMessage: Gen[SignedPsbtMessage] = {
    for {
      psbt <- PSBTGenerators.arbitraryPSBT
    } yield SignedPsbtMessage(psbt)
  }

  def signedTxMessage: Gen[SignedTxMessage] = {
    for {
      tx <- TransactionGenerators.transaction
    } yield SignedTxMessage(tx)
  }

  def restartRoundMessage: Gen[RestartRoundMessage] = {
    for {
      roundParams <- roundParameters
      nonceMsg <- nonceMsg
    } yield RestartRoundMessage(roundParams, nonceMsg)
  }

  def cancelRegistrationMessage: Gen[CancelRegistrationMessage] = {
    for {
      roundId <- CryptoGenerators.doubleSha256Digest
      nonce <- CryptoGenerators.schnorrNonce
    } yield CancelRegistrationMessage(nonce, roundId)
  }
}
