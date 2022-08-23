package com.lnvortex.server

import akka.http.scaladsl.model.ws.Message
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.lnvortex.core.ClientStatus._
import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.core.util.EnvUtil
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration._
import scala.util.Random

class RemixNetworkingTest
    extends DualClientFixture
    with ClientServerTestUtils
    with EmbeddedPg {
  override val isNetworkingTest = false
  override val outputScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val changeScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val inputScriptType: ScriptType = WITNESS_V1_TAPROOT

  val interval: FiniteDuration =
    if (torEnabled) 500.milliseconds else 100.milliseconds

  val maxTries: Int = if (EnvUtil.isCI) 500 else 50

  override def getDummyQueue: SourceQueueWithComplete[Message] = Source
    .queue[Message](bufferSize = 10,
                    OverflowStrategy.backpressure,
                    maxConcurrentOffers = 2)
    .toMat(BroadcastHub.sink)(Keep.left)
    .run()

  it must "complete the remix" in { case (clientA, clientB, coordinator) =>
    for {
      addrA <- clientA.vortexWallet.getNewAddress(
        coordinator.roundParams.outputType)

      addrB <- clientB.vortexWallet.getNewAddress(
        coordinator.roundParams.outputType)

      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      // don't select all coins
      utxosA <- clientA.listCoins().map(c => Random.shuffle(c).take(1))
      _ = clientA.queueCoins(utxosA.map(_.outputReference), addrA)
      utxosB <- clientB.listCoins().map(c => Random.shuffle(c).take(1))
      _ = clientB.queueCoins(utxosB.map(_.outputReference), addrB)

      txId <- coordinator.completedTxP.future.map(_.txIdBE)
      nextCoordinator <- coordinator.nextCoordinatorP.future

      addrA <- clientA.vortexWallet.getNewAddress(
        nextCoordinator.roundParams.outputType)

      addrB <- clientB.vortexWallet.getNewAddress(
        nextCoordinator.roundParams.outputType)

      _ <- TestAsyncUtil.awaitCondition(
        () => {
          clientA.getCurrentRoundDetails.order == KnownRound.order &&
            clientB.getCurrentRoundDetails.order == KnownRound.order
        },
        interval = interval,
        maxTries = maxTries
      )

      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      // don't select all coins
      utxosA <- clientA.listCoins().map { coins =>
        coins
          .filter(_.outPoint.txIdBE == txId)
          .filter(_.amount == coordinator.config.roundAmount)
      }
      coinsA = Random.shuffle(utxosA.map(_.outputReference)).take(1)
      _ = clientA.queueCoins(coinsA, addrA)

      utxosB <- clientB.listCoins().map { coins =>
        coins.filterNot(_.outPoint.txIdBE == txId)
      }
      coinsB = Random.shuffle(utxosB.map(_.outputReference)).take(1)
      _ = clientB.queueCoins(coinsB, addrB)

      // await completion of the round
      _ <- nextCoordinator.completedTxP.future
      _ <- nextCoordinator.nextCoordinatorP.future

      roundDbs <- nextCoordinator.roundDAO.findAll()

      coinsA <- clientA.listCoins()
      coinsB <- clientB.listCoins()
    } yield {
      assert(roundDbs.size >= 3)

      val changeA = coinsA.filter(_.isChange)
      assert(changeA.size == 1, s"${changeA.size} != 1")
      assert(changeA.forall(_.anonSet == 1))

      val changeB = coinsB.filter(_.isChange)
      assert(changeB.size == 2, s"${changeB.size} != 2")
      assert(changeB.forall(_.anonSet == 1))

      assert(coinsA.count(_.anonSet == 3) == 1)
      assert(coinsB.count(_.anonSet == 2) == 2)
    }
  }
}
