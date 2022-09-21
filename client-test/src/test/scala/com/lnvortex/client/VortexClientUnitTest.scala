package com.lnvortex.client

import com.lnvortex.bitcoind.BitcoindVortexWallet
import com.lnvortex.client.VortexClientException._
import com.lnvortex.core._
import com.lnvortex.core.api.CoordinatorAddress
import com.lnvortex.testkit.LnVortexTestUtils
import org.bitcoins.core.config.MainNet
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.{DoubleSha256Digest, ECPublicKey}
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.bitcoins.testkit.util.BitcoinSAsyncTest

import scala.reflect.io.Directory

class VortexClientUnitTest
    extends BitcoinSAsyncTest
    with CachedBitcoindV23
    with LnVortexTestUtils
    with EmbeddedPg {

  implicit val (config, _) = getTestConfigs()

  private lazy val clientF = cachedBitcoindWithFundsF.flatMap { bitcoind =>
    val wallet = BitcoindVortexWallet(bitcoind)
    val client = new VortexClient(wallet, CoordinatorAddress.dummy)
    config.start().map(_ => client)
  }

  val round: RoundParameters = RoundParameters(
    version = 0,
    roundId = DoubleSha256Digest.empty,
    amount = Satoshis(200_000),
    coordinatorFee = Satoshis.zero,
    publicKey = ECPublicKey.freshPublicKey.schnorrPublicKey,
    time = TimeUtil.currentEpochSecond,
    inputType = ScriptType.WITNESS_V0_KEYHASH,
    outputType = ScriptType.WITNESS_V0_KEYHASH,
    changeType = ScriptType.WITNESS_V0_KEYHASH,
    minPeers = 3,
    maxPeers = 5,
    status = "hello world",
    title = None,
    feeRate = SatoshisPerVirtualByte.one
  )

  private val nonce = ECPublicKey.freshPublicKey.schnorrNonce

  private val address = BitcoinAddress(
    "bcrt1qtw00tth6p80rag47u5ustnv702g36lcvahul88")

  private val nodeId = NodeId(ECPublicKey.dummy)

  it must "fail to create with a different network" in {
    cachedBitcoindWithFundsF.map { bitcoind =>
      val wallet = BitcoindVortexWallet(bitcoind)
      val badAddr = CoordinatorAddress.dummy.copy(network = MainNet)
      val ex =
        intercept[IllegalArgumentException](new VortexClient(wallet, badAddr))

      assert(
        ex.getMessage.contains(
          "Coordinator address network must match wallet network"))
    }
  }

  it must "set requeue" in {
    for {
      client <- clientF
      _ = client.setRequeue(true)
      state = client.getCurrentRoundDetails
    } yield assert(state.requeue)
  }

  it must "handle updated fee rates at NoDetails state" in {
    for {
      client <- clientF
      _ = client.setRoundDetails(NoDetails(false))
      _ = client.updateFeeRate(SatoshisPerVirtualByte.one)
    } yield succeed
  }

  it must "fail to ask nonce at NoDetails state" in {
    for {
      client <- clientF
      _ = client.setRoundDetails(NoDetails(false))
      ex <- recoverToExceptionIf[IllegalStateException](client.askNonce())
    } yield assert(ex.getMessage.contains("Cannot ask nonce at state"))
  }

  it must "fail to store nonce at NoDetails state" in {

    for {
      client <- clientF
      _ = client.setRoundDetails(NoDetails(false))
      ex = intercept[IllegalStateException](client.storeNonce(nonce))
    } yield assert(ex.getMessage.contains("Cannot store nonce at state"))
  }

  it must "fail to cancel a non-existent registration" in {
    for {
      client <- clientF
      _ = client.setRoundDetails(NoDetails(false))
      ex <- recoverToExceptionIf[IllegalStateException](
        client.cancelRegistration())
    } yield assert(ex.getMessage.contains("No registration to cancel"))
  }

  it must "fail to no queue coins" in {
    for {
      client <- clientF
      ex = intercept[IllegalArgumentException](
        client.queueCoins(Vector.empty, address))
      ex2 = intercept[IllegalArgumentException](
        client.queueCoins(Vector.empty, nodeId, None))
    } yield {
      assert(ex.getMessage.contains("Must include inputs"))
      assert(ex2.getMessage.contains("Must include inputs"))
    }
  }

  it must "fail to queue coins at an invalid state" in {
    for {
      client <- clientF
      _ = client.setRoundDetails(NoDetails(false))

      coins <- client.listCoins()
      outputRefs = coins.map(_.outputReference)

      ex = intercept[IllegalStateException](
        client.queueCoins(outputRefs, address))
      ex2 = intercept[IllegalStateException](
        client.queueCoins(outputRefs, nodeId, None))
    } yield {
      assert(ex.getMessage.contains("At invalid state"))
      assert(ex2.getMessage.contains("At invalid state"))
    }
  }

  it must "fail to queue coins of wrong type" in {
    val params = round.copy(inputType = ScriptType.PUBKEYHASH)

    for {
      client <- clientF
      _ = client.setRoundDetails(ReceivedNonce(requeue = false, params, nonce))

      coins <- client.listCoins()
      outputRefs = coins.map(_.outputReference)

      ex = intercept[InvalidInputException](
        client.queueCoins(outputRefs, address))
      ex2 <- recoverToExceptionIf[InvalidInputException](
        client.queueCoins(outputRefs, nodeId, None))
    } yield {
      assert(
        ex.getMessage.contains(s"Error. Must use ${params.inputType} inputs"))
      assert(
        ex2.getMessage.contains(s"Error. Must use ${params.inputType} inputs"))
    }
  }

  it must "fail to queue coins with a different target address type" in {
    val taproot = BitcoinAddress(
      "bcrt1pyrlxcwzd42gn55xhpkwwjp2tgqsu88cxeu6ge5j7ase62nu332zq68weyf")

    for {
      client <- clientF
      _ = client.setRoundDetails(ReceivedNonce(requeue = false, round, nonce))

      coins <- client.listCoins()
      outputRefs = coins.map(_.outputReference)

      ex = intercept[InvalidTargetOutputException](
        client.queueCoins(outputRefs, taproot))
    } yield assert(
      ex.getMessage.contains(s"Error. Must use ${round.outputType} address"))
  }

  it must "fail to queue coins with a different network target address" in {
    val mainnet = BitcoinAddress("bc1q6mp2dt97x4nddcwscqad8ef6lpppgwee79kctc")

    for {
      client <- clientF
      _ = client.setRoundDetails(ReceivedNonce(requeue = false, round, nonce))

      coins <- client.listCoins()
      outputRefs = coins.map(_.outputReference)

      ex = intercept[InvalidTargetOutputException](
        client.queueCoins(outputRefs, mainnet))
    } yield assert(
      ex.getMessage.contains("Error. Address is not for the correct network"))
  }

  it must "fail to queue duplicate coins" in {
    for {
      client <- clientF
      _ = client.setRoundDetails(ReceivedNonce(requeue = false, round, nonce))

      coins <- client.listCoins()
      outputRefs = coins.map(_.outputReference) :+ coins.head.outputReference

      ex = intercept[InvalidInputException](
        client.queueCoins(outputRefs, address))
      ex2 <- recoverToExceptionIf[InvalidInputException](
        client.queueCoins(outputRefs, nodeId, None))
    } yield {
      assert(
        ex.getMessage.contains("Cannot have inputs from duplicate addresses"))
      assert(
        ex2.getMessage.contains("Cannot have inputs from duplicate addresses"))
    }
  }

  it must "fail to queue coins with not enough funding" in {
    val params = round.copy(amount = Satoshis.max)

    for {
      client <- clientF
      _ = client.setRoundDetails(ReceivedNonce(requeue = false, params, nonce))

      coins <- client.listCoins()
      outputRefs = coins.map(_.outputReference)

      ex = intercept[InvalidInputException](
        client.queueCoins(outputRefs, address))
      ex2 <- recoverToExceptionIf[InvalidInputException](
        client.queueCoins(outputRefs, nodeId, None))
    } yield {
      assert(
        ex.getMessage.contains(
          "Must select more inputs to find round, needed "))
      assert(
        ex2.getMessage.contains(
          "Must select more inputs to find round, needed "))
    }
  }

  it must "fail to queue coins without minimal selection" in {
    val params = round.copy(amount = Satoshis.one)

    for {
      client <- clientF
      _ = client.setRoundDetails(ReceivedNonce(requeue = false, params, nonce))

      coins <- client.listCoins()
      outputRefs = coins.map(_.outputReference)

      ex = intercept[InvalidInputException](
        client.queueCoins(outputRefs, address))
      ex2 <- recoverToExceptionIf[InvalidInputException](
        client.queueCoins(outputRefs, nodeId, None))
    } yield {
      assert(
        ex.getMessage.contains(
          "Must select minimal inputs for target amount, please deselect some"))
      assert(
        ex2.getMessage.contains(
          "Must select minimal inputs for target amount, please deselect some"))
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    val directory = new Directory(config.baseDatadir.toFile)
    directory.deleteRecursively()
    ()
  }
}
