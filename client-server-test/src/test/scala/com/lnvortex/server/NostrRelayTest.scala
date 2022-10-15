package com.lnvortex.server

import com.lnvortex.testkit.NostrTestFixture
import org.bitcoins.testkit.async.TestAsyncUtil

class NostrRelayTest extends NostrTestFixture {

  it must "get the coordinator from the nostr relay" in {
    case (clientManger, _) =>
      for {
        _ <- clientManger.start()
        _ <- TestAsyncUtil.awaitCondition(
          () => clientManger.extraCoordinators.nonEmpty,
          maxTries = 200)
      } yield assert(clientManger.extraCoordinators.nonEmpty)
  }
}
