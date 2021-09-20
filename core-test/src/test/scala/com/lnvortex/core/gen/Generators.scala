package com.lnvortex.core.gen

import com.lnvortex.core._
import org.bitcoins.testkitcore.gen._
import org.scalacheck.Gen

object Generators {

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
      amount <- CurrencyUnitGenerator.positiveSatoshis
      fee <- CurrencyUnitGenerator.positiveSatoshis
      inFee <- CurrencyUnitGenerator.positiveSatoshis
      outFee <- CurrencyUnitGenerator.positiveSatoshis
      pubkey <- CryptoGenerators.schnorrPublicKey
      time <- NumberGenerator.uInt64
    } yield {
      MixDetails(version, roundId, amount, fee, inFee, outFee, pubkey, time)
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

  def inputReference: Gen[InputReference] = {
    for {
      outputRef <- TransactionGenerators.outputReference
      scriptWit <- WitnessGenerators.scriptWitness
    } yield InputReference(outputRef, scriptWit)
  }

  def registerInputs: Gen[RegisterInputs] = {
    for {
      numInputs <- Gen.choose(1, 7)
      inputs <- Gen.listOfN(numInputs, inputReference)
      blindedOutput <- CryptoGenerators.fieldElement
      changeOutput <- TransactionGenerators.output
    } yield RegisterInputs(inputs.toVector, blindedOutput, changeOutput)
  }

  def blindedSig: Gen[BlindedSig] = {
    for {
      blindOutputSig <- CryptoGenerators.fieldElement
    } yield BlindedSig(blindOutputSig)
  }

  def bobMessage: Gen[BobMessage] = {
    for {
      sig <- CryptoGenerators.schnorrDigitalSignature
      output <- TransactionGenerators.output
    } yield BobMessage(sig, output)
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
}
