package com.lnvortex.server

import com.lnvortex.core.RoundDetails.getRoundParamsOpt
import com.lnvortex.testkit._
import lnrpc.DisconnectPeerRequest
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import java.net.InetSocketAddress
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

class ClientServerPairNetworkingTest
    extends ClientServerPairFixture
    with EmbeddedPg
    with LnVortexTestUtils {
  override val isNetworkingTest = true
  override val outputScriptType: ScriptType = WITNESS_V0_SCRIPTHASH
  override val inputScriptType: ScriptType = WITNESS_V0_KEYHASH
  override val changeScriptType: ScriptType = WITNESS_V0_KEYHASH

  val interval: FiniteDuration =
    if (torEnabled) 500.milliseconds else 100.milliseconds

  it must "cancel a registration and ask nonce again" in {
    case (client, _, peerLnd) =>
      for {
        nodeId <- peerLnd.nodeId
        _ <- client.askNonce()

        // don't select all coins
        utxos <- client.listCoins().map(c => Random.shuffle(c).take(1))
        _ <- client.queueCoins(utxos.map(_.outputReference), nodeId, None)

        _ <- client.cancelRegistration()
        _ <- client.askNonce()
      } yield succeed
  }

  it must "cancel multiple times" in { case (client, _, peerLnd) =>
    for {
      nodeId <- peerLnd.nodeId
      _ <- client.askNonce()

      // don't select all coins
      utxos <- client.listCoins().map(c => Random.shuffle(c).take(1))
      _ <- client.queueCoins(utxos.map(_.outputReference), nodeId, None)

      _ <- client.cancelRegistration()
      _ <- client.askNonce()
      _ <- client.cancelRegistration()
      _ <- client.askNonce()
      _ <- client.cancelRegistration()
    } yield succeed
  }

  it must "get an updated fee rate" in { case (client, coordinator, _) =>
    val starting = getRoundParamsOpt(client.getCurrentRoundDetails).get.feeRate
    for {
      sent <- coordinator.sendUpdatedFeeRate()
      _ <- TestAsyncUtil.awaitCondition(
        () =>
          getRoundParamsOpt(
            client.getCurrentRoundDetails).get.feeRate != starting,
        interval = interval)
    } yield {
      val newFeeRate =
        getRoundParamsOpt(client.getCurrentRoundDetails).get.feeRate
      assert(newFeeRate == sent)
      assert(starting != newFeeRate)
    }
  }

  it must "open a channel" in { case (client, coordinator, peerLnd) =>
    for {
      nodeId <- peerLnd.nodeId
      _ <- client.askNonce()
      roundId = coordinator.getCurrentRoundId

      // don't select all coins
      utxos <- client.listCoins().map(c => Random.shuffle(c).take(1))
      _ <- client.queueCoins(utxos.map(_.outputReference), nodeId, None)

      _ <- coordinator.beginInputRegistration()
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.inputsDAO.findAll().map(_.size == utxos.size),
        interval = interval,
        maxTries = 500)
      _ <- coordinator.beginOutputRegistration()
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
        interval = interval,
        maxTries = 500)
      // wait until we construct the unsigned tx
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        interval = interval,
        maxTries = 500)
      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        interval = interval,
        maxTries = 500)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until peerLnd sees new channel
      _ <- TestAsyncUtil.awaitConditionF(
        () => peerLnd.listChannels().map(_.nonEmpty),
        interval = interval,
        maxTries = 500)

      roundDbs <- coordinator.roundDAO.findAll()
    } yield assert(roundDbs.size == 2)
  }

  it must "connect and open a channel" in {
    case (client, coordinator, peerLnd) =>
      for {
        // disconnect
        nodeId <- peerLnd.nodeId
        req = DisconnectPeerRequest(nodeId.hex)
        _ <- client.vortexWallet.lndRpcClient.lnd.disconnectPeer(req)
        port = peerLnd.instance
          .asInstanceOf[LndInstanceLocal]
          .listenBinding
          .getPort

        _ <- client.askNonce()
        roundId = coordinator.getCurrentRoundId

        // don't select all coins
        utxos <- client.listCoins().map(c => Random.shuffle(c).take(1))
        _ <- client.queueCoins(
          outputRefs = utxos.map(_.outputReference),
          nodeId = nodeId,
          peerAddrOpt =
            Some(InetSocketAddress.createUnresolved("localhost", port)))

        _ <- coordinator.beginInputRegistration()
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.inputsDAO.findAll().map(_.size == utxos.size),
          interval = interval,
          maxTries = 500)
        _ <- coordinator.beginOutputRegistration()
        // wait until outputs are registered
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.outputsDAO.findAll().map(_.nonEmpty),
          interval = interval,
          maxTries = 500)
        // wait until we construct the unsigned tx
        // use getRound because we could start the new round
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
          interval = interval,
          maxTries = 500)
        // wait until the tx is signed
        // use getRound because we could start the new round
        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
          interval = interval,
          maxTries = 500)

        // Mine some blocks
        _ <- coordinator.bitcoind.getNewAddress.flatMap(
          coordinator.bitcoind.generateToAddress(6, _))

        // wait until peerLnd sees new channel
        _ <- TestAsyncUtil.awaitConditionF(
          () => peerLnd.listChannels().map(_.nonEmpty),
          interval = interval,
          maxTries = 500)

        roundDbs <- coordinator.roundDAO.findAll()
      } yield assert(roundDbs.size == 2)
  }
}
