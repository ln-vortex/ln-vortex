package com.lnvortex.testkit

import com.lnvortex.bitcoind.BitcoindVortexWallet
import com.lnvortex.client.VortexClient
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import com.typesafe.config.ConfigFactory
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
        implicit val (_, serverConf) = getTestConfigs()

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator <- VortexCoordinator.initialize(bitcoind)
          server = new VortexHttpServer(coordinator)
          _ <- server.start()
          addr <- server.bindingP.future.map(_.localAddress)

          host =
            if (addr.getHostString == "0:0:0:0:0:0:0:0") "127.0.0.1"
            else addr.getHostString

          netConfig = ConfigFactory.parseString(
            s"""vortex.coordinator = "$host:${addr.getPort}" """)
          clientConfig = getTestConfigs(Vector(netConfig))._1
          _ <- clientConfig.start()

          vortexClient = VortexClient(BitcoindVortexWallet(bitcoind))(
            system,
            clientConfig)
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
