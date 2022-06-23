package com.lnvortex.server

import akka.testkit.TestActorRef
import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.crypto.{CryptoUtil, ECPrivateKey, Sha256Digest}
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.DurationInt

class RemixTest
    extends DualClientFixture
    with ClientServerTestUtils
    with EmbeddedPg {
  override lazy val pgEnabled: Boolean = true
  override val isNetworkingTest = false
  override val outputScriptType: ScriptType = WITNESS_V0_KEYHASH

  val testActor: TestActorRef[Nothing] = TestActorRef("Remix-test")

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

      _ <- coordinator.newRound()
      _ = clientA.setRound(coordinator.mixDetails)
      _ = clientB.setRound(coordinator.mixDetails)

      _ <- completeOnChainRound(Some(tx.txIdBE),
                                peerId,
                                peerId,
                                clientA,
                                clientB,
                                coordinator)

      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      roundDbs <- coordinator.roundDAO.findAll()

      coinsA <- clientA.listCoins()
      coinsB <- clientB.listCoins()
    } yield {
      assert(roundDbs.size >= 3)

      assert(coinsA.exists(_.anonSet >= 2))
      assert(coinsB.exists(_.anonSet >= 2))
    }
  }
}
