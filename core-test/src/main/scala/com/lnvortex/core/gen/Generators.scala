package com.lnvortex.core.gen

import com.lnvortex.core._
import org.bitcoins.core.protocol.script.EmptyScriptPubKey
import org.bitcoins.core.protocol.transaction.OutputReference
import org.bitcoins.core.script.ScriptType
import org.bitcoins.testkitcore.gen._
import org.scalacheck.Gen

object Generators {

  def unspentCoin: Gen[UnspentCoin] = {
    for {
      addr <- AddressGenerator.bitcoinAddress
      amt <- CurrencyUnitGenerator.positiveSatoshis
      outpoint <- TransactionGenerators.outPoint
      confirmed <- NumberGenerator.bool
      anonSet <- NumberGenerator.positiveInts
      change <- NumberGenerator.bool
    } yield {
      UnspentCoin(addr, amt, outpoint, confirmed, anonSet, change)
    }
  }

  def askMixDetails: Gen[AskMixDetails] = {
    for {
      network <- ChainParamsGenerator.bitcoinNetworkParams
    } yield {
      AskMixDetails(network)
    }
  }

  def validScriptType: Gen[ScriptType] = {
    Gen.oneOf(ScriptType.WITNESS_V0_KEYHASH,
              ScriptType.WITNESS_V0_SCRIPTHASH,
              ScriptType.WITNESS_V1_TAPROOT)
  }

  def mixDetails: Gen[MixDetails] = {
    for {
      version <- NumberGenerator.uInt16
      roundId <- CryptoGenerators.doubleSha256Digest
      amount <- CurrencyUnitGenerator.positiveSatoshis
      coordinatorFee <- CurrencyUnitGenerator.positiveSatoshis
      pubkey <- CryptoGenerators.schnorrPublicKey
      time <- NumberGenerator.uInt64
      inputType <- validScriptType
      outputType <- validScriptType
      changeType <- validScriptType
      maxPeers <- NumberGenerator.uInt16
      status <- StringGenerators.genUTF8String
    } yield {
      MixDetails(
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

  def askNonce: Gen[AskNonce] = {
    for {
      roundId <- CryptoGenerators.doubleSha256Digest
    } yield AskNonce(roundId)
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
      changeSpk <- ScriptGenerators.scriptPubKey
        .map(_._1)
        .suchThat(_ != EmptyScriptPubKey)
      isNone <- NumberGenerator.bool
      changeSpkOpt = if (isNone) None else Some(changeSpk)
    } yield RegisterInputs(inputs.toVector, blindedOutput, changeSpkOpt)
  }

  def blindedSig: Gen[BlindedSig] = {
    for {
      blindOutputSig <- CryptoGenerators.fieldElement
    } yield BlindedSig(blindOutputSig)
  }

  def registerMixOutput: Gen[RegisterMixOutput] = {
    for {
      sig <- CryptoGenerators.schnorrDigitalSignature
      output <- TransactionGenerators.output
    } yield RegisterMixOutput(sig, output)
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
      mixDetails <- mixDetails
      nonceMsg <- nonceMsg
    } yield RestartRoundMessage(mixDetails, nonceMsg)
  }

  def cancelRegistrationMessage: Gen[CancelRegistrationMessage] = {
    for {
      roundId <- CryptoGenerators.doubleSha256Digest
      nonce <- CryptoGenerators.schnorrNonce
    } yield CancelRegistrationMessage(nonce, roundId)
  }
}
