package com.lnvortex.server

import com.lnvortex.testkit.HttpTestFixture
import org.bitcoins.core.config.TestNet3

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
            address == "qq3olsfzuzzwxq7276oa7tp5hpc7htcpjl3xe2e7c3nkoobnbfdbvuid.onion:12523")
        case None => fail("Could not find Taproot Testnet coordinator")
      }
    }
  }
}
