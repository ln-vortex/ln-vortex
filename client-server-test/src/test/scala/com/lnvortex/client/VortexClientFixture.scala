package com.lnvortex.client

import akka.actor.ActorSystem
import com.lnvortex.core._
import com.typesafe.config._
import org.bitcoins.core.currency._
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestClient
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import java.nio.file._

/** A trait that is useful if you need Lnd fixtures for your test suite */
trait VortexClientFixture extends BitcoinSFixture with CachedBitcoindV21 {

  def tmpDir(): Path = Files.createTempDirectory("ln-vortex-")

  def getTestConfig(config: Config*)(implicit
      system: ActorSystem): VortexAppConfig = {
    val overrideConf = ConfigFactory.parseString {
      s"""
         |bitcoin-s {
         |  proxy.enabled = true
         |  tor.enabled = true
         |  tor.use-random-ports = false
         |}
      """.stripMargin
    }
    VortexAppConfig(tmpDir(), overrideConf +: config: _*)
  }

  override type FixtureParam = VortexClient

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[VortexClient](
      () => {
        implicit val conf: VortexAppConfig = getTestConfig()
        for {
          bitcoind <- cachedBitcoindWithFundsF

          client = LndRpcTestClient.fromSbtDownload(Some(bitcoind))
          lnd <- client.start()

          addrA <- lnd.getNewAddress
          addrB <- lnd.getNewAddress
          addrC <- lnd.getNewAddress

          _ <- bitcoind.sendToAddress(addrA, Bitcoins(1))
          _ <- bitcoind.sendToAddress(addrB, Bitcoins(2))
          _ <- bitcoind.sendToAddress(addrC, Bitcoins(3))
          _ <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))

          height <- bitcoind.getBlockCount

          // Await synced
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.getInfo.map(_.syncedToChain))
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.getInfo.map(_.blockHeight == height))
          // Await funded
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.walletBalance().map(_.balance == Bitcoins(6)))

          // Await utxos
          _ <- TestAsyncUtil.awaitConditionF(() =>
            lnd.listUnspent.map(_.nonEmpty))

        } yield VortexClient(lnd)
      },
      { vortex =>
        for {
          _ <- vortex.lndRpcClient.stop()
        } yield ()
      }
    )(test)
  }
}
