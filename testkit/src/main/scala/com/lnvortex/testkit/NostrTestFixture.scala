package com.lnvortex.testkit

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import com.lnvortex.bitcoind.BitcoindVortexWallet
import com.lnvortex.client.VortexClientManager
import com.lnvortex.server.coordinator.VortexCoordinator
import com.lnvortex.server.networking.VortexHttpServer
import com.typesafe.config.ConfigFactory
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome
import org.testcontainers.containers.wait.strategy.Wait

import scala.reflect.io.Directory

trait NostrTestFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with LnVortexTestUtils
    with ForAllTestContainer
    with EmbeddedPg {

  override val container: GenericContainer =
    GenericContainer("scsibug/nostr-rs-relay:latest",
                     exposedPorts = Seq(8080),
                     waitStrategy = Wait.forListeningPort())

  lazy val url =
    s"ws://${container.containerIpAddress}:${container.mappedPort(8080)}"

  override type FixtureParam =
    (VortexClientManager[BitcoindVortexWallet], VortexHttpServer)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[(VortexClientManager[BitcoindVortexWallet],
                          VortexHttpServer)](
      () => {
        val clientNostrConfig =
          ConfigFactory.parseString(s"vortex.nostr.relays = [\"$url\"]")
        val serverNostrConfig =
          ConfigFactory.parseString(s"coordinator.nostr.relays = [\"$url\"]")

        implicit val (clientConfig, serverConf) =
          getTestConfigs(Vector(clientNostrConfig, serverNostrConfig))

        for {
          _ <- serverConf.start()
          bitcoind <- cachedBitcoindWithFundsF
          coordinator <- VortexCoordinator.initialize(bitcoind)
          server = new VortexHttpServer(coordinator)
          _ <- server.start()

          _ <- clientConfig.start()

          vortexClient = new VortexClientManager(
            BitcoindVortexWallet(bitcoind))(system, clientConfig)
        } yield (vortexClient, server)
      },
      { case (client, server) =>
        val coordinator = server.currentCoordinator
        for {
          _ <- client.vortexWallet.stop()
          _ <- client.stop()
          _ <- client.config.stop()

          _ <- coordinator.config.dropAll().map(_ => coordinator.config.clean())
          _ = coordinator.stop()
          _ <- coordinator.config.stop()

          _ <- server.stop()
        } yield {
          val directory = new Directory(coordinator.config.baseDatadir.toFile)
          directory.deleteRecursively()
          ()
        }
      }
    )(test)
  }
}
