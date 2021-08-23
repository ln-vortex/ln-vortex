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

libraryDependencies ++= Deps.core

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
