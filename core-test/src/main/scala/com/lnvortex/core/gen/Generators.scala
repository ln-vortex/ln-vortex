package com.lnvortex.core.gen

import com.lnvortex.core.InputRegistrationType._
import com.lnvortex.core._
import org.bitcoins.core.protocol.script.EmptyScriptPubKey
import org.bitcoins.core.protocol.transaction.OutputReference
import org.bitcoins.testkitcore.gen._
import org.scalacheck.Gen

object Generators {

  def inputRegistrationType: Gen[InputRegistrationType] =
    Gen.oneOf(AsyncInputRegistrationType, SynchronousInputRegistrationType)

  def askMixDetails: Gen[AskMixDetails] = {
    for {
      network <- ChainParamsGenerator.bitcoinNetworkParams
    } yield {
      AskMixDetails(network)
    }
  }

  def mixDetails: Gen[MixDetails] = {
    for {
      version <- NumberGenerator.uInt16
      roundId <- CryptoGenerators.doubleSha256Digest
      regType <- inputRegistrationType
      amount <- CurrencyUnitGenerator.positiveSatoshis
      mixFee <- CurrencyUnitGenerator.positiveSatoshis
      pubkey <- CryptoGenerators.schnorrPublicKey
      time <- NumberGenerator.uInt64
    } yield {
      MixDetails(version, roundId, regType, amount, mixFee, pubkey, time)
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
    } yield AskInputs(roundId, inFee, outFee)
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
}
