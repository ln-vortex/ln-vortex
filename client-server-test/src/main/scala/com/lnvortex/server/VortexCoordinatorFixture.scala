package com.lnvortex.server

import akka.actor.ActorSystem
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import java.nio.file.{Files, Path}

/** A trait that is useful if you need Lnd fixtures for your test suite */
trait VortexCoordinatorFixture extends BitcoinSFixture with CachedBitcoindV21 {

  def tmpDir(): Path = Files.createTempDirectory("ln-vortex-")

  def getTestConfig(config: Config*)(implicit
      system: ActorSystem): VortexCoordinatorAppConfig = {
    val overrideConf = ConfigFactory.parseString {
      s"""
         |bitcoin-s {
         |  proxy.enabled = true
         |  tor.enabled = true
         |  tor.use-random-ports = false
         |}
      """.stripMargin
    }
    VortexCoordinatorAppConfig(tmpDir(), overrideConf +: config: _*)
  }

  override type FixtureParam = VortexCoordinator

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[VortexCoordinator](
      () => {
        implicit val conf: VortexCoordinatorAppConfig = getTestConfig()
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
        } yield ()
      }
    )(test)
  }
}
