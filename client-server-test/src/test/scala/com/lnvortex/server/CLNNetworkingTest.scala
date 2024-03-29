package com.lnvortex.server

import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import java.net.InetSocketAddress
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

class CLNNetworkingTest
    extends CLNClientServerPairFixture
    with EmbeddedPg
    with LnVortexTestUtils {
  override val isNetworkingTest = true
  override val outputScriptType: ScriptType = WITNESS_V0_SCRIPTHASH
  override val inputScriptType: ScriptType = WITNESS_V0_KEYHASH
  override val changeScriptType: ScriptType = WITNESS_V0_KEYHASH

  val interval: FiniteDuration =
    if (torEnabled) 500.milliseconds else 100.milliseconds

  it must "open a channel" in { case (client, coordinator, peerLnd) =>
    for {
      nodeId <- peerLnd.nodeId
      port = peerLnd.instance
        .asInstanceOf[LndInstanceLocal]
        .listenBinding
        .getPort

      _ <- client.vortexWallet.clightning.connect(nodeId, "localhost", port)

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
        nodeId <- peerLnd.nodeId
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

        // wait until cln sees new channel
        _ <- TestAsyncUtil.awaitConditionF(
          () => client.listChannels().map(_.exists(_.remotePubkey == nodeId)),
          interval = interval,
          maxTries = 500)

        roundDbs <- coordinator.roundDAO.findAll()
      } yield {
        assert(roundDbs.size == 2)
      }
  }
}
