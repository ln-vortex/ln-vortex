package com.lnvortex.testkit

import akka.actor.ActorSystem
import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.BitcoinSTestAppConfig.{
  configWithEmbeddedDb,
  ProjectType
}
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
         |coordinator {
         |  listen = "127.0.0.1:$listenPort"
         |  maxPeers = 2
         |  coordinatorFee = 10000
         |  roundInterval = 180s
         |  roundAmount = 1000000
         |  inputRegistrationTime = 120s
         |  outputRegistrationTime = 120s
         |  signingTime = 120s
         |}
      """.stripMargin
    }

    val clientPg =
      configWithEmbeddedDb(
        Some(ProjectType.Unknown(VortexAppConfig.moduleName)),
        () => pgUrl())

    val serverPg = configWithEmbeddedDb(
      Some(ProjectType.Unknown(VortexCoordinatorAppConfig.moduleName)),
      () => pgUrl())

    val clientConf =
      VortexAppConfig(dir, Vector(clientPg, serverPg, overrideConf) ++ config)
    val serverConf =
      VortexCoordinatorAppConfig(
        dir,
        Vector(clientPg, serverPg, overrideConf) ++ config)

    (clientConf, serverConf)
  }
}
