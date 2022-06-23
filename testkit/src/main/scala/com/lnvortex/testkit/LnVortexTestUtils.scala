package com.lnvortex.testkit

import akka.actor.ActorSystem
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.BitcoinSTestAppConfig.configWithEmbeddedDb
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.util.FileUtil

import java.io.File
import java.nio.file.Path
import scala.util.Properties

trait LnVortexTestUtils { self: EmbeddedPg =>

  def tmpDir(): Path = new File(
    s"/tmp/ln-vortex-test/${FileUtil.randomDirName}/").toPath

  val torEnabled: Boolean = Properties
    .envOrNone("TOR")
    .isDefined

  def getTestConfigs(config: Vector[Config] = Vector.empty)(implicit
      system: ActorSystem): (VortexAppConfig, VortexCoordinatorAppConfig) = {
    val listenPort = RpcUtil.randomPort
    val dir = tmpDir()
    val overrideConf = ConfigFactory.parseString {
      s"""
         |bitcoin-s {
         |  proxy.enabled = $torEnabled
         |  tor.enabled = $torEnabled
         |  tor.use-random-ports = false
         |}
         |
         |vortex.coordinator = "127.0.0.1:$listenPort"
         |
         |coordinator {
         |  listen = "0.0.0.0:$listenPort"
         |  maxPeers = 2
         |  mixFee = 10000
         |  mixInterval = 60m
         |  mixAmount = 1000000
         |  inputRegistrationTime = 20s
         |  outputRegistrationTime = 20s
         |  signingTime = 20s
         |}
      """.stripMargin
    }

    val pg = configWithEmbeddedDb(None, () => pgUrl())
    val clientConf = VortexAppConfig(dir, Vector(pg, overrideConf) ++ config)
    val serverConf =
      VortexCoordinatorAppConfig(dir, Vector(pg, overrideConf) ++ config)

    (clientConf, serverConf)
  }
}
