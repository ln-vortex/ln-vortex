package com.lnvortex.testkit

import com.lnvortex.client.VortexClient
import com.lnvortex.lnd.LndVortexWallet
import com.lnvortex.server.coordinator.VortexCoordinator
import com.typesafe.config.ConfigFactory
import org.bitcoins.core.script.ScriptType
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait ClientServerPairFixture
    extends BitcoinSFixture
    with CachedBitcoindV21
    with LnVortexTestUtils
    with EmbeddedPg {

  override type FixtureParam =
    (VortexClient[LndVortexWallet], VortexCoordinator, LndRpcClient)

  def isNetworkingTest: Boolean

  def outputScriptType: ScriptType

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(
        VortexClient[LndVortexWallet],
        VortexCoordinator,
        LndRpcClient)](
      () => {
        val scriptTypeConfig =
          ConfigFactory.parseString(
            s"coordinator.outputScriptType = $outputScriptType")
        implicit val (_, serverConf) = getTestConfigs(Vector(scriptTypeConfig))

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator = VortexCoordinator(bitcoind)
          _ <- coordinator.start()
          (addr, _) <- coordinator.serverBindF

          _ = assert(serverConf.outputScriptType == outputScriptType)

          host =
            if (addr.getHostString == "0:0:0:0:0:0:0:0") "127.0.0.1"
            else addr.getHostString

          netConfig = ConfigFactory.parseString(
            s"""vortex.coordinator = "$host:${addr.getPort}" """)
          clientConfig = getTestConfigs(Vector(netConfig))._1
          _ <- clientConfig.start()

          (lnd, peerLnd) <- LndTestUtils.createNodePair(bitcoind)
          client = VortexClient(LndVortexWallet(lnd))(system, clientConfig)
          _ <- client.start()

          _ <- LndRpcTestUtil.connectLNNodes(lnd, peerLnd)

          // wait for it to receive mix details
          _ <- TestAsyncUtil.awaitCondition(() =>
            client.getCurrentRoundDetails.order > 0)

          // don't send message if not networking test
          _ = if (!isNetworkingTest) coordinator.connectionHandlerMap.clear()
        } yield (client, coordinator, peerLnd)
      },
      { case (client, coordinator, peerLnd) =>
        for {
          _ <- peerLnd.stop()
          _ <- client.vortexWallet.stop()
          _ <- client.stop()
          _ <- client.config.stop()

          _ <- coordinator.stop()
          _ <- coordinator.config.stop()
        } yield {
          val directory = new Directory(coordinator.config.baseDatadir.toFile)
          directory.deleteRecursively()
          ()
        }
      }
    )(test)
  }
}
