package com.lnvortex.testkit

import akka.actor.ActorSystem
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.coordinator.VortexCoordinator
import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.bitcoins.testkit.util.FileUtil
import org.scalatest.FutureOutcome

import java.io.File
import java.nio.file.Path
import scala.reflect.io.Directory

trait VortexCoordinatorFixture extends BitcoinSFixture with CachedBitcoindV21 {

  def tmpDir(): Path = new File(
    s"/tmp/ln-vortex-test/${FileUtil.randomDirName}/").toPath

  def getTestConfig(config: Config*)(implicit
      system: ActorSystem): VortexCoordinatorAppConfig = {
    val listenPort = RpcUtil.randomPort
    val overrideConf = ConfigFactory.parseString {
      s"""
         |bitcoin-s {
         |  proxy.enabled = true
         |  tor.enabled = true
         |  tor.use-random-ports = false
         |}
         |vortex {
         |  listen = "0.0.0.0:$listenPort"
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
        } yield {
          val directory = new Directory(coordinator.config.baseDatadir.toFile)
          directory.deleteRecursively()
          ()
        }
      }
    )(test)
  }
}
