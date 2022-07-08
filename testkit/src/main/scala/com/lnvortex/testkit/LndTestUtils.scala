package com.lnvortex.testkit

import akka.actor.ActorSystem
import com.lnvortex.lnd.LndVortexWallet
import org.bitcoins.core.currency._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.lnd._
import org.bitcoins.testkit.util.FileUtil

import scala.concurrent._

trait LndTestUtils {

  val lndVersion: Option[String] = Some("20220628-01")

  def fundLndNodes(
      bitcoind: BitcoindRpcClient,
      client: LndRpcClient,
      otherClient: LndRpcClient,
      scriptType: ScriptType)(implicit ec: ExecutionContext): Future[Unit] = {
    val addressType = LndVortexWallet.addressTypeFromScriptType(scriptType)

    for {
      addrA <- client.getNewAddress(addressType)
      addrB <- client.getNewAddress(addressType)
      addrC <- client.getNewAddress(addressType)
      addrD <- otherClient.getNewAddress(addressType)
      addrE <- otherClient.getNewAddress(addressType)
      addrF <- otherClient.getNewAddress(addressType)

      _ <- bitcoind.sendMany(
        Map(addrA -> Bitcoins(1), addrB -> Bitcoins(2), addrC -> Bitcoins(3)))
      _ <- bitcoind.sendMany(
        Map(addrD -> Bitcoins(1), addrE -> Bitcoins(2), addrF -> Bitcoins(3)))
      _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))
    } yield ()
  }

  def createNodePair(bitcoind: BitcoindRpcClient, inputType: ScriptType)(
      implicit ec: ExecutionContext): Future[(LndRpcClient, LndRpcClient)] = {
    val actorSystemA =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientA =
      LndRpcTestClient.fromSbtDownload(Some(bitcoind), lndVersion)(actorSystemA)

    val actorSystemB =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientB =
      LndRpcTestClient.fromSbtDownload(Some(bitcoind), lndVersion)(actorSystemB)

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

      _ <- fundLndNodes(bitcoind, client, otherClient, inputType)

      _ <- TestAsyncUtil.awaitConditionF(() => isSynced)
      _ <- TestAsyncUtil.awaitConditionF(() => isFunded)
    } yield (client, otherClient)
  }
}

object LndTestUtils extends LndTestUtils
