package com.lnvortex.testkit

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.testkit.LnVortexTestUtils.getTestConfigs
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import scala.reflect.io.Directory

trait VortexCoordinatorFixture extends BitcoinSFixture with CachedBitcoindV21 {

  override type FixtureParam = VortexCoordinator

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[VortexCoordinator](
      () => {
        implicit val conf: VortexCoordinatorAppConfig = getTestConfigs()._2
        for {
          _ <- conf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator = VortexCoordinator(bitcoind)
          _ <- coordinator.start()
        } yield coordinator
      },
      { coordinator =>
        for {
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
