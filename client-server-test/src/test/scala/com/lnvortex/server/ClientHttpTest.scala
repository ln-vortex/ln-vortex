package com.lnvortex.server

import com.lnvortex.testkit.HttpTestFixture
import org.bitcoins.core.config.{RegTest, TestNet3}

class ClientHttpTest extends HttpTestFixture {

  it must "ping" in { case (client, _) =>
    client.ping().map { res =>
      assert(res)
    }
  }

  it must "get coordinators" in { case (client, _) =>
    client.getCoordinators(TestNet3).map { coordinators =>
      coordinators.find(_.name == "Taproot Testnet") match {
        case Some(taproot) =>
          assert(taproot.name == "Taproot Testnet")
          assert(taproot.network == TestNet3)
          val address =
            s"${taproot.onion.getHostString}:${taproot.onion.getPort}"
          assert(
            address == "y74gsfy6u7s73jl53x52gcaqv3b7f76rntevwymk2ovziytz5p35tiad.onion:12523")
        case None => fail("Could not find Taproot Testnet coordinator")
      }
    }
  }

  it must "get round params" in { case (client, server) =>
    client.getRoundParams(RegTest).map { params =>
      assert(params == server.currentCoordinator.roundParams)
    }
  }

  it must "fail to get get round params for a different network" in {
    case (client, _) =>
      recoverToSucceededIf[RuntimeException](client.getRoundParams(TestNet3))
  }
}
