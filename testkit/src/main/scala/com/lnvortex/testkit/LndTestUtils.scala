package com.lnvortex.testkit

import akka.actor.ActorSystem
import org.bitcoins.core.currency._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.lnd._
import org.bitcoins.testkit.util.FileUtil

import scala.concurrent._

trait LndTestUtils {

  def fundLndNodes(
      bitcoind: BitcoindRpcClient,
      client: LndRpcClient,
      otherClient: LndRpcClient)(implicit
      ec: ExecutionContext): Future[Unit] = {
    for {
      addrA <- client.getNewAddress
      addrB <- client.getNewAddress
      addrC <- client.getNewAddress
      addrD <- otherClient.getNewAddress
      addrE <- otherClient.getNewAddress
      addrF <- otherClient.getNewAddress

      _ <- bitcoind.sendMany(
        Map(addrA -> Bitcoins(1), addrB -> Bitcoins(2), addrC -> Bitcoins(3)))
      _ <- bitcoind.sendMany(
        Map(addrD -> Bitcoins(1), addrE -> Bitcoins(2), addrF -> Bitcoins(3)))
      _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))
    } yield ()
  }

  def createNodePair(bitcoind: BitcoindRpcClient)(implicit
      ec: ExecutionContext): Future[(LndRpcClient, LndRpcClient)] = {
    val actorSystemA =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientA = LndRpcTestClient
      .fromSbtDownload(Some(bitcoind))(actorSystemA)

    val actorSystemB =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientB = LndRpcTestClient
      .fromSbtDownload(Some(bitcoind))(actorSystemB)

    val clientsF = for {
      a <- clientA.start()
      b <- clientB.start()
    } yield (a, b)

    def isSynced: Future[Boolean] = for {
      (client, otherClient) <- clientsF

      infoA <- client.getInfo
      infoB <- otherClient.getInfo
    } yield infoA.syncedToChain && infoB.syncedToChain

    def isFunded: Future[Boolean] = for {
      (client, otherClient) <- clientsF

      balA <- client.walletBalance()
      balB <- otherClient.walletBalance()
    } yield balA.confirmedBalance == Bitcoins(6) &&
      balB.confirmedBalance == Bitcoins(6)

    for {
      (client, otherClient) <- clientsF

      _ <- fundLndNodes(bitcoind, client, otherClient)

      _ <- TestAsyncUtil.awaitConditionF(() => isSynced)
      _ <- TestAsyncUtil.awaitConditionF(() => isFunded)
    } yield (client, otherClient)
  }
}

object LndTestUtils extends LndTestUtils
