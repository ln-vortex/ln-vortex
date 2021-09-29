package com.lnvortex.client.lnd

import com.lnvortex.testkit.LndChannelOpenerFixture
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.script.P2WSHWitnessSPKV0
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.lnd.LndRpcTestUtil

import scala.concurrent.Future

class LndChannelOpenerTest extends LndChannelOpenerFixture {
  behavior of "LndChannelOpener"

  it must "get channel funding info" in { case (_, lndA, lndB) =>
    val opener = LndChannelOpener(lndA)
    val amount = Satoshis(100000)

    for {
      _ <- LndRpcTestUtil.connectLNNodes(lndA, lndB)
      nodeId <- lndB.nodeId
      fundDetails <- opener.initPSBTChannelOpen(nodeId = nodeId,
                                                peerAddrOpt = None,
                                                fundingAmount = amount,
                                                privateChannel = false)
    } yield {
      assert(fundDetails.amount == amount)
      assert(fundDetails.address.scriptPubKey.isInstanceOf[P2WSHWitnessSPKV0])
    }
  }

  it must "open a channel with external funding" in {
    case (bitcoind, lndA, lndB) =>
      val opener = LndChannelOpener(lndA)
      val amount = Satoshis(100000)

      for {
        _ <- LndRpcTestUtil.connectLNNodes(lndA, lndB)
        preChannelsA <- lndA.listChannels()
        preChannelsB <- lndB.listChannels()
        _ = assert(preChannelsA.isEmpty)
        _ = assert(preChannelsB.isEmpty)

        nodeId <- lndB.nodeId
        fundDetails <- opener.initPSBTChannelOpen(nodeId = nodeId,
                                                  peerAddrOpt = None,
                                                  fundingAmount = amount,
                                                  privateChannel = false)

        // construct psbt
        psbt <- bitcoind
          .walletCreateFundedPsbt(
            Vector.empty,
            Map(fundDetails.address -> fundDetails.amount))
          .map(_.psbt)
        // fund channel with psbt
        _ <- opener.fundPendingChannel(fundDetails.id, psbt)

        midChannelsA <- lndA.listChannels()
        midChannelsB <- lndB.listChannels()
        _ = assert(midChannelsA.isEmpty)
        _ = assert(midChannelsB.isEmpty)

        res <- bitcoind.walletProcessPSBT(psbt)
        tx <- Future.fromTry(res.psbt.extractTransactionAndValidate)
        _ <- bitcoind.sendRawTransaction(tx)
        _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))

        // await for lnds to see channel
        _ <- TestAsyncUtil.awaitConditionF(() =>
          lndA.listChannels().map(_.nonEmpty))
        _ <- TestAsyncUtil.awaitConditionF(() =>
          lndB.listChannels().map(_.nonEmpty))
      } yield succeed
  }

  it must "cancel a channel" in { case (bitcoind, lndA, lndB) =>
    val opener = LndChannelOpener(lndA)
    val amount = Satoshis(100000)

    for {
      _ <- LndRpcTestUtil.connectLNNodes(lndA, lndB)
      preChannelsA <- lndA.listChannels()
      preChannelsB <- lndB.listChannels()
      _ = assert(preChannelsA.isEmpty)
      _ = assert(preChannelsB.isEmpty)

      nodeId <- lndB.nodeId
      fundDetails <- opener.initPSBTChannelOpen(nodeId = nodeId,
                                                peerAddrOpt = None,
                                                fundingAmount = amount,
                                                privateChannel = false)

      // construct psbt
      psbt <- bitcoind
        .walletCreateFundedPsbt(Vector.empty,
                                Map(fundDetails.address -> fundDetails.amount))
        .map(_.psbt)
      // fund channel with psbt
      _ <- opener.fundPendingChannel(fundDetails.id, psbt)

      midChannelsA <- lndA.listChannels()
      midChannelsB <- lndB.listChannels()
      _ = assert(midChannelsA.isEmpty)
      _ = assert(midChannelsB.isEmpty)

      // cancel channel
      txid = psbt.transaction.txIdBE
      vout = psbt.transaction.outputs.indexWhere(
        _.scriptPubKey == fundDetails.address.scriptPubKey)
      outPoint = TransactionOutPoint(txid, UInt32(vout))
      _ <- opener.cancelChannel(outPoint)

      res <- bitcoind.walletProcessPSBT(psbt)
      tx <- Future.fromTry(res.psbt.extractTransactionAndValidate)
      _ <- bitcoind.sendRawTransaction(tx)
      _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))

      // await for lnds to see channel
      _ <- TestAsyncUtil.awaitConditionF(() =>
        lndA.listChannels().map(_.isEmpty))
      _ <- TestAsyncUtil.awaitConditionF(() =>
        lndB.listChannels().map(_.isEmpty))
    } yield succeed
  }
}
