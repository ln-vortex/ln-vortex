package com.lnvortex.client

import akka.testkit.TestActorRef
import com.lnvortex.client.VortexClient.knownVersions
import com.lnvortex.client.VortexClientException._
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindingTweaks
import com.lnvortex.testkit.VortexClientFixture
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._
import org.bitcoins.testkitcore.gen.NumberGenerator

class VortexClientTest extends VortexClientFixture {
  behavior of "VortexClient"

  val roundParams: RoundParameters = RoundParameters(
    version = UInt16.zero,
    roundId = DoubleSha256Digest.empty,
    amount = Satoshis(200000),
    coordinatorFee = Satoshis.zero,
    publicKey = ECPublicKey.freshPublicKey.schnorrPublicKey,
    time = UInt64.zero,
    inputType = ScriptType.WITNESS_V0_KEYHASH,
    outputType = ScriptType.WITNESS_V0_KEYHASH,
    changeType = ScriptType.WITNESS_V0_KEYHASH,
    maxPeers = UInt16(5),
    status = "hello world"
  )

  val nonce: SchnorrNonce = ECPublicKey.freshPublicKey.schnorrNonce

  val dummyTweaks: BlindingTweaks =
    BlindingTweaks.freshBlindingTweaks(roundParams.publicKey, nonce)

  it must "fail to process an unknown version AskRoundParameters" in {
    vortexClient =>
      forAll(NumberGenerator.uInt16.suchThat(!knownVersions.contains(_))) {
        version =>
          assertThrows[RuntimeException](
            vortexClient.setRound(roundParams.copy(version = version)))
      }
  }

  it must "create missing utxos in its database" in { vortexClient =>
    for {
      coins <- vortexClient.listCoins()
      _ <- vortexClient.utxoDAO.createMissing(coins.map(_.outPoint))

      utxos <- vortexClient.utxoDAO.findAll()
      _ = assert(coins.forall(c => utxos.exists(_.outPoint == c.outPoint)))

      // do it again, make sure it doesn't fail
      _ <- vortexClient.utxoDAO.createMissing(coins.map(_.outPoint))

      utxos2 <- vortexClient.utxoDAO.findAll()
    } yield assert(utxos == utxos2)
  }

  it must "cancel a registration" in { vortexClient =>
    val lnd = vortexClient.vortexWallet

    for {
      nodeId <- lnd.lndRpcClient.nodeId
      utxos <- vortexClient.listCoins()
      refs = utxos.map(_.outputReference)

      testState = InputsScheduled(round = roundParams,
                                  nonce = nonce,
                                  inputs = refs,
                                  addressOpt = None,
                                  nodeIdOpt = Some(nodeId),
                                  peerAddrOpt = None)
      _ = vortexClient.setRoundDetails(testState)

      _ = vortexClient.handlerP.success(TestActorRef("test"))
      _ <- vortexClient.cancelRegistration()
    } yield assert(
      vortexClient.getCurrentRoundDetails == KnownRound(roundParams))
  }

  it must "fail to sign a psbt with no fee info" in { vortexClient =>
    val lnd = vortexClient.vortexWallet

    for {
      nodeId <- lnd.lndRpcClient.nodeId
      utxos <- vortexClient.listCoins()
      refs = utxos.map(_.outputReference)
      addrA <- lnd.getNewAddress(roundParams.changeType)
      addrB <- lnd.getNewAddress(roundParams.outputType)
      change = TransactionOutput(Satoshis(599800000), addrA.scriptPubKey)
      mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(
        inputs = refs,
        addressOpt = None,
        nodeIdOpt = Some(nodeId),
        peerAddrOpt = None,
        changeSpkOpt = Some(change.scriptPubKey),
        chanId = Sha256Digest.empty.bytes,
        mixOutput = mix,
        tweaks = dummyTweaks
      )
      testState = MixOutputRegistered(roundParams,
                                      Satoshis.zero,
                                      Satoshis.zero,
                                      Satoshis.zero,
                                      nonce,
                                      testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change, mix)
      tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
      res <- recoverToSucceededIf[TooLowOfFeeException](
        vortexClient.validateAndSignPsbt(PSBT.fromUnsignedTx(tx)))
    } yield res
  }

  it must "fail to sign a psbt with a missing mix output" in { vortexClient =>
    val lnd = vortexClient.vortexWallet

    for {
      nodeId <- lnd.lndRpcClient.nodeId
      utxos <- lnd.listCoins()
      refs = utxos.map(_.outputReference)

      addrA <- lnd.getNewAddress(roundParams.changeType)
      addrB <- lnd.getNewAddress(roundParams.outputType)
      change = TransactionOutput(Satoshis(599800000), addrA.scriptPubKey)
      mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(
        inputs = refs,
        addressOpt = None,
        nodeIdOpt = Some(nodeId),
        peerAddrOpt = None,
        changeSpkOpt = Some(change.scriptPubKey),
        chanId = Sha256Digest.empty.bytes,
        mixOutput = mix,
        tweaks = dummyTweaks
      )
      testState = MixOutputRegistered(roundParams,
                                      Satoshis.zero,
                                      Satoshis.zero,
                                      Satoshis.zero,
                                      nonce,
                                      testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change)
      tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
      psbt = PSBT.fromUnsignedTx(tx)
      res <- recoverToSucceededIf[InvalidMixedOutputException](
        vortexClient.validateAndSignPsbt(psbt))
    } yield res
  }

  it must "fail to sign a psbt with a missing change output" in {
    vortexClient =>
      val lnd = vortexClient.vortexWallet

      for {
        nodeId <- lnd.lndRpcClient.nodeId
        utxos <- vortexClient.listCoins()
        refs = utxos.map(_.outputReference)
        addrA <- lnd.getNewAddress(roundParams.changeType)
        addrB <- lnd.getNewAddress(roundParams.outputType)
        change = TransactionOutput(Satoshis(599800000), addrA.scriptPubKey)
        mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(
          inputs = refs,
          addressOpt = None,
          nodeIdOpt = Some(nodeId),
          peerAddrOpt = None,
          changeSpkOpt = Some(change.scriptPubKey),
          chanId = Sha256Digest.empty.bytes,
          mixOutput = mix,
          tweaks = dummyTweaks
        )
        testState = MixOutputRegistered(roundParams,
                                        Satoshis.zero,
                                        Satoshis.zero,
                                        Satoshis.zero,
                                        nonce,
                                        testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(mix)
        tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
        psbt = PSBT.fromUnsignedTx(tx)
        res <- recoverToSucceededIf[InvalidChangeOutputException](
          vortexClient.validateAndSignPsbt(psbt))
      } yield res
  }

  it must "fail to sign a psbt with a too low change output" in {
    vortexClient =>
      val lnd = vortexClient.vortexWallet

      for {
        nodeId <- lnd.lndRpcClient.nodeId
        utxos <- vortexClient.listCoins()
        refs = utxos.map(_.outputReference)
        addrA <- lnd.getNewAddress(roundParams.changeType)
        addrB <- lnd.getNewAddress(roundParams.outputType)
        change = TransactionOutput(Satoshis(599700000), addrA.scriptPubKey)
        mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(
          inputs = refs,
          addressOpt = None,
          nodeIdOpt = Some(nodeId),
          peerAddrOpt = None,
          changeSpkOpt = Some(change.scriptPubKey),
          chanId = Sha256Digest.empty.bytes,
          mixOutput = mix,
          tweaks = dummyTweaks
        )
        testState = MixOutputRegistered(roundParams,
                                        Satoshis.zero,
                                        Satoshis.zero,
                                        Satoshis.zero,
                                        nonce,
                                        testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(change, mix)
        tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
        psbt = PSBT.fromUnsignedTx(tx)
        res <- recoverToSucceededIf[InvalidChangeOutputException](
          vortexClient.validateAndSignPsbt(psbt))
      } yield res
  }

  it must "fail to sign a psbt with a missing input" in { vortexClient =>
    val lnd = vortexClient.vortexWallet

    for {
      nodeId <- lnd.lndRpcClient.nodeId
      utxos <- vortexClient.listCoins()
      _ = require(utxos.nonEmpty)
      refs = utxos.map(_.outputReference)
      addrA <- lnd.getNewAddress(roundParams.changeType)
      addrB <- lnd.getNewAddress(roundParams.outputType)
      change = TransactionOutput(Satoshis(599800000), addrA.scriptPubKey)
      mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(
        inputs = refs,
        addressOpt = None,
        nodeIdOpt = Some(nodeId),
        peerAddrOpt = None,
        changeSpkOpt = Some(change.scriptPubKey),
        chanId = Sha256Digest.empty.bytes,
        mixOutput = mix,
        tweaks = dummyTweaks
      )
      testState = MixOutputRegistered(roundParams,
                                      Satoshis.zero,
                                      Satoshis.zero,
                                      Satoshis.zero,
                                      nonce,
                                      testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change, mix)
      tx = BaseTransaction(Int32.two, inputs.tail, outputs, UInt32.zero)
      psbt = PSBT.fromUnsignedTx(tx)
      res <- recoverToSucceededIf[MissingInputsException](
        vortexClient.validateAndSignPsbt(psbt))
    } yield res
  }

  it must "fail to sign a psbt with a locktime too far in the future" in {
    vortexClient =>
      val lnd = vortexClient.vortexWallet

      for {
        nodeId <- lnd.lndRpcClient.nodeId
        utxos <- vortexClient.listCoins()
        _ = require(utxos.nonEmpty)
        refs = utxos.map(_.outputReference)
        addrA <- lnd.getNewAddress(roundParams.changeType)
        addrB <- lnd.getNewAddress(roundParams.outputType)
        change = TransactionOutput(Satoshis(599800000), addrA.scriptPubKey)
        mix = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(
          inputs = refs,
          addressOpt = None,
          nodeIdOpt = Some(nodeId),
          peerAddrOpt = None,
          changeSpkOpt = Some(change.scriptPubKey),
          chanId = Sha256Digest.empty.bytes,
          mixOutput = mix,
          tweaks = dummyTweaks
        )
        testState = MixOutputRegistered(roundParams,
                                        Satoshis.zero,
                                        Satoshis.zero,
                                        Satoshis.zero,
                                        nonce,
                                        testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(change, mix)
        tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.max)
        psbt = PSBT.fromUnsignedTx(tx)
        res <- recoverToSucceededIf[BadLocktimeException](
          vortexClient.validateAndSignPsbt(psbt))
      } yield res
  }
}
