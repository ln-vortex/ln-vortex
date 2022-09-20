package com.lnvortex.core

import org.bitcoins.core.config.BitcoinNetworks
import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class VortexUtilsTest extends BitcoinSUnitTest {

  it must "have all unique default ports" in {
    val ports = BitcoinNetworks.knownNetworks.flatMap { net =>
      val netPort = VortexUtils.getDefaultPort(net)
      val clientRpcPort = VortexUtils.getDefaultClientRpcPort(net)
      val coordRpcPort = VortexUtils.getDefaultCoordinatorRpcPort(net)
      Vector(netPort, clientRpcPort, coordRpcPort)
    }

    assert(ports.distinct.size == ports.size)
  }
}
