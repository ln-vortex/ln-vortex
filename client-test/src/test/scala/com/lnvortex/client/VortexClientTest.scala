package com.lnvortex.client

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
import org.bitcoins.core.script.ScriptType.SCRIPTHASH
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.testkitcore.gen.NumberGenerator

class VortexClientTest extends VortexClientFixture {
  behavior of "VortexClient"

  val roundParams: RoundParameters = RoundParameters(
    version = 0,
    roundId = DoubleSha256Digest.empty,
    amount = Satoshis(200000),
    coordinatorFee = Satoshis.zero,
    publicKey = ECPublicKey.freshPublicKey.schnorrPublicKey,
    time = TimeUtil.currentEpochSecond,
    inputType = ScriptType.WITNESS_V1_TAPROOT,
    outputType = ScriptType.WITNESS_V0_KEYHASH,
    changeType = ScriptType.WITNESS_V0_KEYHASH,
    minPeers = 3,
    maxPeers = 5,
    status = "hello world",
    title = None,
    feeRate = SatoshisPerVirtualByte.one
  )

  val nonce: SchnorrNonce = ECPublicKey.freshPublicKey.schnorrNonce

  val dummyTweaks: BlindingTweaks =
    BlindingTweaks.freshBlindingTweaks(roundParams.publicKey, nonce)

  it must "fail to process an unknown version AskRoundParameters" in {
    vortexClient =>
      forAllAsync(
        NumberGenerator.uInt16
          .map(_.toInt)
          .suchThat(!knownVersions.contains(_))) { version =>
        recoverToSucceededIf[RuntimeException](
          vortexClient.setRound(roundParams.copy(version = version)))
      }
  }

  it must "create missing utxos in its database" in { vortexClient =>
    for {
      coins <- vortexClient.listCoins()
      _ <- vortexClient.utxoDAO.createMissing(coins)

      utxos <- vortexClient.utxoDAO.findAll()
      _ = assert(coins.forall(c => utxos.exists(_.outPoint == c.outPoint)))

      // do it again, make sure it doesn't fail
      _ <- vortexClient.utxoDAO.createMissing(coins)

      utxos2 <- vortexClient.utxoDAO.findAll()
    } yield assert(utxos == utxos2)
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
      targetOutput = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(
        inputs = refs,
        addressOpt = None,
        nodeIdOpt = Some(nodeId),
        peerAddrOpt = None,
        changeSpkOpt = Some(change.scriptPubKey),
        chanId = Sha256Digest.empty.bytes,
        targetOutput = targetOutput,
        tweaks = dummyTweaks
      )
      testState = OutputRegistered(requeue = false,
                                   round = roundParams,
                                   inputFee = Satoshis.zero,
                                   outputFee = Satoshis.zero,
                                   changeOutputFee = Satoshis.zero,
                                   nonce = nonce,
                                   initDetails = testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change, targetOutput)
      tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
      res <- recoverToSucceededIf[InvalidInputTypeException](
        vortexClient.validateAndSignPsbt(PSBT.fromUnsignedTx(tx)))
    } yield res
  }

  it must "fail to sign a psbt with a missing target output" in {
    vortexClient =>
      val lnd = vortexClient.vortexWallet

      for {
        nodeId <- lnd.lndRpcClient.nodeId
        utxos <- lnd.listCoins()
        refs = utxos.map(_.outputReference)

        addrA <- lnd.getNewAddress(roundParams.changeType)
        addrB <- lnd.getNewAddress(roundParams.outputType)
        change = TransactionOutput(Satoshis(599800000), addrA.scriptPubKey)
        target = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(
          inputs = refs,
          addressOpt = None,
          nodeIdOpt = Some(nodeId),
          peerAddrOpt = None,
          changeSpkOpt = Some(change.scriptPubKey),
          chanId = Sha256Digest.empty.bytes,
          targetOutput = target,
          tweaks = dummyTweaks
        )
        testState = OutputRegistered(requeue = false,
                                     round = roundParams,
                                     inputFee = Satoshis.zero,
                                     outputFee = Satoshis.zero,
                                     changeOutputFee = Satoshis.zero,
                                     nonce = nonce,
                                     initDetails = testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(change)
        tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)
        psbt = PSBT.fromUnsignedTx(tx)
        res <- recoverToSucceededIf[InvalidTargetOutputException](
          vortexClient.validateAndSignPsbt(psbt))
      } yield res
  }

  it must "fail to sign a psbt with invalid output types" in { vortexClient =>
    val lnd = vortexClient.vortexWallet

    for {
      nodeId <- lnd.lndRpcClient.nodeId
      utxos <- lnd.listCoins()
      refs = utxos.map(_.outputReference)

      addrA <- lnd.getNewAddress(roundParams.changeType)
      addrB <- lnd.getNewAddress(SCRIPTHASH)
      change = TransactionOutput(Satoshis(599800000), addrA.scriptPubKey)
      target = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(
        inputs = refs,
        addressOpt = None,
        nodeIdOpt = Some(nodeId),
        peerAddrOpt = None,
        changeSpkOpt = Some(change.scriptPubKey),
        chanId = Sha256Digest.empty.bytes,
        targetOutput = target,
        tweaks = dummyTweaks
      )
      testState = OutputRegistered(requeue = false,
                                   round = roundParams,
                                   inputFee = Satoshis.zero,
                                   outputFee = Satoshis.zero,
                                   changeOutputFee = Satoshis.zero,
                                   nonce = nonce,
                                   initDetails = testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change, target)
      tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.zero)

      psbt = PSBT.fromUnsignedTx(tx)
      withUtxos = psbt.inputMaps.zipWithIndex.foldLeft(psbt) {
        case (psbt, (_, index)) =>
          val input = psbt.transaction.inputs(index)
          val utxo = utxos.find(_.outPoint == input.previousOutput).get

          psbt.addWitnessUTXOToInput(utxo.output, index)
      }

      res <- recoverToSucceededIf[InvalidOutputTypeException](
        vortexClient.validateAndSignPsbt(withUtxos))
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
        targetOutput = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(
          inputs = refs,
          addressOpt = None,
          nodeIdOpt = Some(nodeId),
          peerAddrOpt = None,
          changeSpkOpt = Some(change.scriptPubKey),
          chanId = Sha256Digest.empty.bytes,
          targetOutput = targetOutput,
          tweaks = dummyTweaks
        )
        testState = OutputRegistered(requeue = false,
                                     round = roundParams,
                                     inputFee = Satoshis.zero,
                                     outputFee = Satoshis.zero,
                                     changeOutputFee = Satoshis.zero,
                                     nonce = nonce,
                                     initDetails = testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(targetOutput)
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
        targetOutput = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(
          inputs = refs,
          addressOpt = None,
          nodeIdOpt = Some(nodeId),
          peerAddrOpt = None,
          changeSpkOpt = Some(change.scriptPubKey),
          chanId = Sha256Digest.empty.bytes,
          targetOutput = targetOutput,
          tweaks = dummyTweaks
        )
        testState = OutputRegistered(requeue = false,
                                     round = roundParams,
                                     inputFee = Satoshis.zero,
                                     outputFee = Satoshis.zero,
                                     changeOutputFee = Satoshis.zero,
                                     nonce = nonce,
                                     initDetails = testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(change, targetOutput)
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
      targetOutput = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

      testDetails = InitDetails(
        inputs = refs,
        addressOpt = None,
        nodeIdOpt = Some(nodeId),
        peerAddrOpt = None,
        changeSpkOpt = Some(change.scriptPubKey),
        chanId = Sha256Digest.empty.bytes,
        targetOutput = targetOutput,
        tweaks = dummyTweaks
      )
      testState = OutputRegistered(requeue = false,
                                   round = roundParams,
                                   inputFee = Satoshis.zero,
                                   outputFee = Satoshis.zero,
                                   changeOutputFee = Satoshis.zero,
                                   nonce = nonce,
                                   initDetails = testDetails)
      _ = vortexClient.setRoundDetails(testState)

      inputs = refs
        .map(_.outPoint)
        .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
      outputs = Vector(change, targetOutput)
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
        targetOutput = TransactionOutput(Satoshis(200000), addrB.scriptPubKey)

        testDetails = InitDetails(
          inputs = refs,
          addressOpt = None,
          nodeIdOpt = Some(nodeId),
          peerAddrOpt = None,
          changeSpkOpt = Some(change.scriptPubKey),
          chanId = Sha256Digest.empty.bytes,
          targetOutput = targetOutput,
          tweaks = dummyTweaks
        )
        testState = OutputRegistered(requeue = false,
                                     round = roundParams,
                                     inputFee = Satoshis.zero,
                                     outputFee = Satoshis.zero,
                                     changeOutputFee = Satoshis.zero,
                                     nonce = nonce,
                                     initDetails = testDetails)
        _ = vortexClient.setRoundDetails(testState)

        inputs = refs
          .map(_.outPoint)
          .map(TransactionInput(_, EmptyScriptSignature, UInt32.max))
        outputs = Vector(change, targetOutput)
        tx = BaseTransaction(Int32.two, inputs, outputs, UInt32.max)
        psbt = PSBT.fromUnsignedTx(tx)
        res <- recoverToSucceededIf[BadLocktimeException](
          vortexClient.validateAndSignPsbt(psbt))
      } yield res
  }
}
