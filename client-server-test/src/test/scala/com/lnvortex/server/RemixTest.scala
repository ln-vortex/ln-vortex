package com.lnvortex.server

import akka.http.scaladsl.model.ws.Message
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.crypto._
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.DurationInt

class RemixTest
    extends DualClientFixture
    with ClientServerTestUtils
    with EmbeddedPg {
  override val isNetworkingTest = false
  override val outputScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val changeScriptType: ScriptType = WITNESS_V1_TAPROOT
  override val inputScriptType: ScriptType = WITNESS_V1_TAPROOT

  override def getDummyQueue: SourceQueueWithComplete[Message] = Source
    .queue[Message](bufferSize = 10,
                    OverflowStrategy.backpressure,
                    maxConcurrentOffers = 2)
    .toMat(BroadcastHub.sink)(Keep.left)
    .run()

  def peerId: Sha256Digest =
    CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)

  it must "complete the remix" in { case (clientA, clientB, coordinator) =>
    for {
      tx <- completeOnChainRound(None,
                                 peerId,
                                 peerId,
                                 clientA,
                                 clientB,
                                 coordinator)

      nextCoordinator <- coordinator.nextCoordinatorP.future
      _ <- clientA.setRound(nextCoordinator.roundParams)
      _ <- clientB.setRound(nextCoordinator.roundParams)

      _ <- completeOnChainRound(Some(tx.txIdBE),
                                peerId,
                                peerId,
                                clientA,
                                clientB,
                                nextCoordinator)

      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

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
