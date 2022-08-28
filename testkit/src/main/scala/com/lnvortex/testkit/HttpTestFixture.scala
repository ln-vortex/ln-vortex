package com.lnvortex.testkit

import com.lnvortex.bitcoind.BitcoindVortexWallet
import com.lnvortex.client.VortexClient
import com.lnvortex.core.api.CoordinatorAddress
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import org.bitcoins.core.config.RegTest
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait HttpTestFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with LnVortexTestUtils
    with EmbeddedPg {

  override type FixtureParam =
    (VortexClient[BitcoindVortexWallet], VortexCoordinator)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(
        VortexClient[BitcoindVortexWallet],
        VortexCoordinator)](
      () => {
        implicit val (clientConfig, serverConf) = getTestConfigs()

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator <- VortexCoordinator.initialize(bitcoind)
          server = new VortexHttpServer(coordinator)
          _ <- server.start()
          addr <- server.getBinding.map(_.localAddress)

          _ <- clientConfig.start()

          coordinatorAddr = CoordinatorAddress("test", RegTest, addr)
          vortexClient = VortexClient(BitcoindVortexWallet(bitcoind),
                                      coordinatorAddr)(system, clientConfig)
        } yield (vortexClient, coordinator)
      },
      { case (client, coordinator) =>
        for {
          _ <- client.vortexWallet.stop()
          _ <- client.stop()
          _ <- client.config.stop()

          _ <- coordinator.config.dropAll().map(_ => coordinator.config.clean())
          _ = coordinator.stop()
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
