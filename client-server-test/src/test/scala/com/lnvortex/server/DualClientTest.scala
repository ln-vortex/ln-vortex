package com.lnvortex.server

import com.lnvortex.client.RoundDetails.getInitDetailsOpt
import com.lnvortex.testkit.DualClientFixture
import org.bitcoins.testkit.async.TestAsyncUtil

class DualClientTest extends DualClientFixture {

  it must "get nonces from the coordinator" in {
    case (clientA, clientB, coordinator) =>
      for {
        nonceA <- clientA.askNonce()
        nonceB <- clientB.askNonce()
        aliceDbs <- coordinator.aliceDAO.findAll()
      } yield {
        assert(aliceDbs.size == 2)
        assert(aliceDbs.exists(_.nonce == nonceA))
        assert(aliceDbs.exists(_.nonce == nonceB))
      }
  }

  it must "register inputs" in { case (clientA, clientB, coordinator) =>
    for {
      nodeIdA <- clientA.lndRpcClient.nodeId
      nodeIdB <- clientB.lndRpcClient.nodeId
      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      _ <- coordinator.beginInputRegistration()
      // don't select all coins
      utxosA <- clientA.listCoins().map(_.tail)
      _ <- clientA.registerCoins(utxosA.map(_.outPointOpt.get), nodeIdB, None)
      utxosB <- clientB.listCoins().map(_.tail)
      _ <- clientB.registerCoins(utxosB.map(_.outPointOpt.get), nodeIdA, None)
      // give time for messages to send
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO
          .findAll()
          .map(_.size == utxosA.size + utxosB.size))
    } yield succeed
  }

  it must "register inputs & outputs" in {
    case (clientA, clientB, coordinator) =>
      for {
        nodeIdA <- clientA.lndRpcClient.nodeId
        nodeIdB <- clientB.lndRpcClient.nodeId
        _ <- clientA.askNonce()
        _ <- clientB.askNonce()
        _ <- coordinator.beginInputRegistration()
        // don't select all coins
        utxosA <- clientA.listCoins().map(_.tail)
        _ <- clientA.registerCoins(utxosA.map(_.outPointOpt.get), nodeIdB, None)
        utxosB <- clientB.listCoins().map(_.tail)
        _ <- clientB.registerCoins(utxosB.map(_.outPointOpt.get), nodeIdA, None)
        // wait until outputs are registered
        _ <- TestAsyncUtil.awaitConditionF(() =>
          coordinator.inputsDAO
            .findAll()
            .map(_.size == utxosA.size + utxosB.size))
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.outputsDAO.findAll().map(_.size == 2),
          maxTries = 500)
        outputDbs <- coordinator.outputsDAO.findAll()
      } yield {
        val expectedOutputA =
          getInitDetailsOpt(clientA.getCurrentRoundDetails).get.mixOutput
        val expectedOutputB =
          getInitDetailsOpt(clientB.getCurrentRoundDetails).get.mixOutput

        assert(outputDbs.size == 2)
        assert(outputDbs.exists(_.output == expectedOutputA))
        assert(outputDbs.exists(_.output == expectedOutputB))
      }
  }

  it must "sign the psbt" in { case (clientA, clientB, coordinator) =>
    for {
      nodeIdA <- clientA.lndRpcClient.nodeId
      nodeIdB <- clientB.lndRpcClient.nodeId
      roundId = coordinator.getCurrentRoundId

      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      _ <- coordinator.beginInputRegistration()
      // don't select all coins
      utxosA <- clientA.listCoins().map(_.tail)
      _ <- clientA.registerCoins(utxosA.map(_.outPointOpt.get), nodeIdB, None)
      utxosB <- clientB.listCoins().map(_.tail)
      _ <- clientB.registerCoins(utxosB.map(_.outPointOpt.get), nodeIdA, None)
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO
          .findAll()
          .map(_.size == utxosA.size + utxosB.size))
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.size == 2),
        maxTries = 500)
      // wait until we construct the unsigned tx
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        maxTries = 500)

      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        maxTries = 500)
    } yield succeed
  }

  it must "open the channels" in { case (clientA, clientB, coordinator) =>
    for {
      nodeIdA <- clientA.lndRpcClient.nodeId
      nodeIdB <- clientB.lndRpcClient.nodeId
      roundId = coordinator.getCurrentRoundId

      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      _ <- coordinator.beginInputRegistration()
      // don't select all coins
      utxosA <- clientA.listCoins().map(_.tail)
      _ <- clientA.registerCoins(utxosA.map(_.outPointOpt.get), nodeIdB, None)
      utxosB <- clientB.listCoins().map(_.tail)
      _ <- clientB.registerCoins(utxosB.map(_.outPointOpt.get), nodeIdA, None)
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(() =>
        coordinator.inputsDAO
          .findAll()
          .map(_.size == utxosA.size + utxosB.size))
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.size == 2),
        maxTries = 500)
      // wait until we construct the unsigned tx
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        maxTries = 500)

      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        maxTries = 500)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until clientA sees new channels
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientA.lndRpcClient.listChannels().map(_.size == 2),
        maxTries = 500)

      // wait until clientB sees new channels
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientB.lndRpcClient.listChannels().map(_.size == 2),
        maxTries = 500)
    } yield succeed
  }
}
