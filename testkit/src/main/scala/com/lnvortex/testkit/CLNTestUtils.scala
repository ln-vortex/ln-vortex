package com.lnvortex.testkit

import akka.actor.ActorSystem
import com.bitcoins.clightning.rpc.CLightningRpcClient
import com.lnvortex.clightning.CLightningVortexWallet
import com.lnvortex.lnd.LndVortexWallet
import org.bitcoins.core.currency._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.clightning.CLightningRpcTestClient
import org.bitcoins.testkit.lnd._
import org.bitcoins.testkit.util.FileUtil

import scala.concurrent._
import scala.concurrent.duration.DurationInt

trait CLNTestUtils {

  def fundNodes(
      bitcoind: BitcoindRpcClient,
      cln: CLightningRpcClient,
      lnd: LndRpcClient,
      scriptType: ScriptType)(implicit ec: ExecutionContext): Future[Unit] = {
    val clnAddressType =
      CLightningVortexWallet.addressTypeFromScriptType(scriptType)
    val lndAddressType = LndVortexWallet.addressTypeFromScriptType(scriptType)

    for {
      addrA <- cln.getNewAddress(clnAddressType)
      addrB <- cln.getNewAddress(clnAddressType)
      addrC <- cln.getNewAddress(clnAddressType)
      addrD <- lnd.getNewAddress(lndAddressType)
      addrE <- lnd.getNewAddress(lndAddressType)
      addrF <- lnd.getNewAddress(lndAddressType)

      _ <- bitcoind.sendMany(
        Map(addrA -> Bitcoins(1), addrB -> Bitcoins(2), addrC -> Bitcoins(3)))
      _ <- bitcoind.sendMany(
        Map(addrD -> Bitcoins(1), addrE -> Bitcoins(2), addrF -> Bitcoins(3)))
      _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))
    } yield ()
  }

  def createNodePair(
      bitcoind: BitcoindRpcClient,
      inputType: ScriptType)(implicit
      ec: ExecutionContext): Future[(CLightningRpcClient, LndRpcClient)] = {
    val actorSystemA =
      ActorSystem.create("ln-vortex-cln-test-" + FileUtil.randomDirName)
    val clnClient =
      CLightningRpcTestClient.fromSbtDownload(Some(bitcoind), None)(
        actorSystemA)

    val actorSystemB =
      ActorSystem.create("ln-vortex-lnd-test-" + FileUtil.randomDirName)
    val lndClient =
      LndRpcTestClient.fromSbtDownload(Some(bitcoind))(actorSystemB)

    val clientsF = for {
      a <- clnClient.start()
      b <- lndClient.start()
    } yield (a, b)

    def isSynced: Future[Boolean] = for {
      (cln, lnd) <- clientsF
      height <- bitcoind.getBlockCount

      infoA <- cln.getInfo
      infoB <- lnd.getInfo
    } yield infoA.blockheight == height && infoB.syncedToChain

    def isFunded: Future[Boolean] = for {
      (cln, lnd) <- clientsF

      balA <- cln.walletBalance()
      balB <- lnd.walletBalance()
    } yield balA.confirmedBalance == Bitcoins(6) &&
      balB.confirmedBalance == Bitcoins(6)

    for {
      (cln, lnd) <- clientsF

      _ <- fundNodes(bitcoind, cln, lnd, inputType)

      _ <- TestAsyncUtil.awaitConditionF(() => isSynced, interval = 1.second)
      _ <- TestAsyncUtil.awaitConditionF(() => isFunded)
    } yield (cln, lnd)
  }
}

object CLNTestUtils extends CLNTestUtils
