package com.lnvortex.server

import akka.testkit.TestActorRef
import com.lnvortex.testkit._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.ScriptType._
import org.bitcoins.crypto.{CryptoUtil, ECPrivateKey, Sha256Digest}
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil
import scodec.bits._

import scala.concurrent.duration.DurationInt

class RemixTest
    extends DualClientFixture
    with ClientServerTestUtils
    with EmbeddedPg {
  override lazy val pgEnabled: Boolean = true
  override val isNetworkingTest = false
  override val mixScriptType: ScriptType = WITNESS_V0_KEYHASH

  val testActor: TestActorRef[Nothing] = TestActorRef("Remix-test")
  val peerIdA: Sha256Digest = Sha256Digest(ByteVector.low(32))
  val peerIdB: Sha256Digest = Sha256Digest(ByteVector.high(32))

  val peerIdA2: Sha256Digest =
    CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)

  val peerIdB2: Sha256Digest =
    CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)

  it must "complete the remix" in { case (clientA, clientB, coordinator) =>
    for {
      tx <- completeOnChainRound(None,
                                 peerIdA,
                                 peerIdB,
                                 clientA,
                                 clientB,
                                 coordinator)

      _ <- coordinator.newRound()
      _ = clientA.setRound(coordinator.mixDetails)
      _ = clientB.setRound(coordinator.mixDetails)

      _ <- completeOnChainRound(Some(tx.txIdBE),
                                peerIdA2,
                                peerIdB2,
                                clientA,
                                clientB,
                                coordinator)

      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      roundDbs <- coordinator.roundDAO.findAll()
    } yield assert(roundDbs.size >= 3)
  }
}
