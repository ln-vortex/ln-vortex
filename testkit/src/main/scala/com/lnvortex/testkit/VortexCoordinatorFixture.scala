package com.lnvortex.testkit

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait VortexCoordinatorFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with LnVortexTestUtils
    with EmbeddedPg {

  override type FixtureParam = VortexCoordinator

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[VortexCoordinator](
      () => {
        implicit val conf: VortexCoordinatorAppConfig = getTestConfigs()._2
        for {
          bitcoind <- cachedBitcoindWithFundsF
          addr <- bitcoind.getNewAddress
          _ <- bitcoind.generateToAddress(6, addr)
          _ <- conf.start()
          coordinator <- VortexCoordinator.initialize(bitcoind)
        } yield coordinator
      },
      { coordinator =>
        val config = coordinator.config

        for {
          _ <- config.dropAll().map(_ => config.clean())
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
