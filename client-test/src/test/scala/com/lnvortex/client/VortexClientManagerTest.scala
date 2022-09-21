package com.lnvortex.client

import com.lnvortex.testkit.VortexClientManagerFixture

class VortexClientManagerTest extends VortexClientManagerFixture {

  it must "list coins" in { case (client, _) =>
    client.listCoins().map { coins =>
      assert(coins.nonEmpty)
    }
  }

  it must "list channels" in { case (client, _) =>
    client.listChannels().map { channels =>
      assert(channels.isEmpty)
    }
  }

  it must "list transactions" in { case (client, _) =>
    client.listTransactions().map { txs =>
      assert(txs.nonEmpty)
    }
  }
}
