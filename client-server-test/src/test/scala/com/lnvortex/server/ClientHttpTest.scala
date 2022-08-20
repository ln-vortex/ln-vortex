package com.lnvortex.server

import com.lnvortex.testkit.HttpTestFixture

class ClientHttpTest extends HttpTestFixture {

  it must "ping" in { case (client, _) =>
    client.ping().map { res =>
      assert(res)
    }
  }
}
