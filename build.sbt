import com.typesafe.sbt.packager.windows._
import sbt.Resolver

name := "LnVortex"

version := "0.1"

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
    core,
    coreTest,
    client,
    clientTest,
    clientServerTest,
    server,
    serverTest,
    gui
  )
  .dependsOn(
    core,
    coreTest,
    client,
    clientTest,
    clientServerTest,
    server,
    serverTest,
    gui
  )
  .settings(CommonSettings.settings: _*)
  .settings(
    name := "ln-vortex",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "core", libraryDependencies ++= Deps.backend)

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
  .dependsOn(client, coreTest)

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
  .dependsOn(server, coreTest)

lazy val clientServerTest = project
  .in(file("client-server-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "client-server-test",
            libraryDependencies ++= Deps.clientServerTest)
  .dependsOn(client, server, coreTest)

lazy val gui = project
  .in(file("gui"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "gui", libraryDependencies ++= Deps.gui)
  .dependsOn(client)
