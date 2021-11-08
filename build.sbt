import com.typesafe.sbt.packager.windows._
import sbt.Resolver

name := "LnVortex"

version := "1.0"

scalaVersion := "2.13.6"

resolvers += Resolver.sonatypeRepo("snapshots")

enablePlugins(ReproducibleBuildsPlugin,
              JavaAppPackaging,
              GraalVMNativeImagePlugin,
              WindowsPlugin)

Compile / doc := (target.value / "none")
scalacOptions ++= Seq("release", "11")

// general package information (can be scoped to Windows)
maintainer := "Ben Carman <benthecarman@live.com>"
// Will say "Welcome to the <packageSummary> Setup Wizard"
packageSummary := "LN Vortex"
// Will be used for drop down menu in setup wizard
packageDescription := "LN Vortex"

// wix build information
wixProductId := java.util.UUID.randomUUID().toString
wixProductUpgradeId := java.util.UUID.randomUUID().toString

// Adding the wanted wixFeature:
wixFeatures += WindowsFeature(
  id = "shortcuts",
  title = "Shortcuts in start menu",
  desc = "Add shortcuts for execution and uninstall in start menu",
  components = Seq(
    AddShortCuts(Seq("bin/ln-vortex.bat"))
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(
    bitcoind,
    bitcoindTest,
    clightning,
    clightningTest,
    core,
    coreTest,
    client,
    clientTest,
    clientServerTest,
    lnd,
    lndTest,
    server,
    serverTest,
    gui,
    testkit
  )
  .dependsOn(
    bitcoind,
    bitcoindTest,
    clightning,
    clightningTest,
    core,
    coreTest,
    client,
    clientTest,
    clientServerTest,
    lnd,
    lndTest,
    server,
    serverTest,
    gui,
    testkit
  )
  .settings(CommonSettings.settings: _*)
  .settings(
    name := "ln-vortex",
    publish / skip := true
  )

lazy val lnd = project
  .in(file("lnd"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "lnd", libraryDependencies ++= Deps.lndBackend)
  .dependsOn(core)

lazy val lndTest = project
  .in(file("lnd-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "lnd-test")
  .settings(parallelExecution := false)
  .dependsOn(lnd, testkit)

lazy val clightning = project
  .in(file("clightning"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "clightning",
            libraryDependencies ++= Deps.cLightningBackend)
  .dependsOn(core)

lazy val clightningTest = project
  .in(file("clightning-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "clightning-test")
  .settings(parallelExecution := false)
  .dependsOn(clightning, testkit)

lazy val bitcoind = project
  .in(file("bitcoind"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "bitcoind", libraryDependencies ++= Deps.bitcoindBackend)
  .dependsOn(core)

lazy val bitcoindTest = project
  .in(file("bitcoind-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "bitcoind-test")
  .settings(parallelExecution := false)
  .dependsOn(bitcoind, testkit)

lazy val core = project
  .in(file("core"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "core", libraryDependencies ++= Deps.core)

lazy val coreTest = project
  .in(file("core-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "core-test", libraryDependencies ++= Deps.coreTest)
  .dependsOn(core)

lazy val client = project
  .in(file("client"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "client", libraryDependencies ++= Deps.backend)
  .dependsOn(core)

lazy val clientTest = project
  .in(file("client-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "client-test",
            libraryDependencies ++= Deps.clientServerTest)
  .settings(parallelExecution := false)
  .dependsOn(client, testkit)

lazy val server = project
  .in(file("server"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "server", libraryDependencies ++= Deps.server)
  .dependsOn(core)

lazy val serverTest = project
  .in(file("server-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "server-test",
            libraryDependencies ++= Deps.clientServerTest)
  .settings(parallelExecution := false)
  .dependsOn(server, testkit)

lazy val clientServerTest = project
  .in(file("client-server-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "client-server-test",
            libraryDependencies ++= Deps.clientServerTest)
  .settings(parallelExecution := false)
  .dependsOn(client, server, testkit)

lazy val testkit = project
  .in(file("testkit"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "testkit", libraryDependencies ++= Deps.clientServerTest)
  .dependsOn(core, client, server, coreTest, lnd, clightning)

lazy val gui = project
  .in(file("gui"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "gui", libraryDependencies ++= Deps.gui)
  .dependsOn(client)
