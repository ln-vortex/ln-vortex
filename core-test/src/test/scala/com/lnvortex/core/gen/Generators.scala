package com.lnvortex.core.gen

import com.lnvortex.core._
import org.bitcoins.testkitcore.gen._
import org.scalacheck.Gen

object Generators {

  def askMixAdvertisement: Gen[AskMixAdvertisement] = {
    for {
      network <- ChainParamsGenerator.bitcoinNetworkParams
    } yield {
      AskMixAdvertisement(network)
    }
  }

  def mixAdvertisement: Gen[MixAdvertisement] = {
    for {
      version <- NumberGenerator.uInt16
      amount <- CurrencyUnitGenerator.positiveSatoshis
      fee <- CurrencyUnitGenerator.positiveSatoshis
      pubkey <- CryptoGenerators.schnorrPublicKey
      nonce <- CryptoGenerators.schnorrNonce
      time <- NumberGenerator.uInt64
    } yield {
      MixAdvertisement(version, amount, fee, pubkey, nonce, time)
    }
  }

  def aliceInit: Gen[AliceInit] = {
    for {
      numInputs <- Gen.choose(1, 7)
      inputs <- Gen.listOfN(numInputs, TransactionGenerators.outputReference)
      blindedOutput <- CryptoGenerators.fieldElement
      changeOutput <- TransactionGenerators.output
    } yield AliceInit(inputs.toVector, blindedOutput, changeOutput)
  }

  def aliceInitResponse: Gen[AliceInitResponse] = {
    for {
      blindOutputSig <- CryptoGenerators.fieldElement
    } yield AliceInitResponse(blindOutputSig)
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
