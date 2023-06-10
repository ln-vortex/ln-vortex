import com.typesafe.sbt.packager.windows._
import sbt.Resolver
import java.nio.file.Files
import java.security.MessageDigest
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Properties

name := "ln-vortex"

scalaVersion := "2.13.11"

resolvers ++= Resolver.sonatypeOssRepos("snapshots")

enablePlugins(ReproducibleBuildsPlugin,
              JavaAppPackaging,
              GraalVMNativeImagePlugin,
              WindowsPlugin)

Compile / doc := (target.value / "none")
scalacOptions ++= Seq("release", "11")

// general package information (can be scoped to Windows)
maintainer := "benthecarman <benthecarman@live.com>"
// Will say "Welcome to the <packageSummary> Setup Wizard"
packageSummary := "Vortex"
// Will be used for drop down menu in setup wizard
packageDescription := "Vortex"

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

lazy val `ln-vortex` = project
  .in(file("."))
  .aggregate(
    coordinatorCli,
    coordinatorRpc,
    cli,
    config,
    configTest,
    rpcServer,
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
    testkit,
    develop
  )
  .dependsOn(
    coordinatorCli,
    coordinatorRpc,
    cli,
    config,
    configTest,
    rpcServer,
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
    testkit,
    develop
  )
  .settings(CommonSettings.settings: _*)
  .settings(
    name := "ln-vortex",
    publish / skip := true
  )

lazy val config = project
  .in(file("app/config"))
  .settings(CommonSettings.prodSettings: _*)
  .settings(libraryDependencies ++= Deps.config)
  .settings(name := "config")
  .dependsOn(core)
  .settings(coverageExcludedPackages := "com.lnvortex.config")

lazy val configTest = project
  .in(file("app/config-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(libraryDependencies ++= Deps.config)
  .settings(name := "config-test")
  .dependsOn(config, testkit)

lazy val cli = project
  .in(file("app/cli"))
  .settings(CommonSettings.prodSettings: _*)
  .settings(libraryDependencies ++= Deps.cli)
  .settings(name := "vortex-cli")
  .dependsOn(config)
  .settings(coverageExcludedPackages := "com.lnvortex.cli")

lazy val rpcServer = project
  .in(file("app/rpc-server"))
  .settings(CommonSettings.appSettings: _*)
  .settings(CommonSettings.dockerSettings: _*)
  .settings(CommonSettings.dockerBuildxSettings: _*)
  .settings(jlinkIgnoreMissingDependency := CommonSettings.jlinkIgnore)
  .settings(libraryDependencies ++= Deps.rpcServer)
  .settings(bashScriptExtraDefines ++= IO.readLines(
    baseDirectory.value / "src" / "universal" / "rpc-server-extra-startup-script.sh"))
  .settings(name := "vortexd")
  .dependsOn(client, lnd, clightning, bitcoind, config)
  .enablePlugins(
    JavaAppPackaging,
    JlinkPlugin,
    DockerPlugin,
    // JlinkPlugin,
    // needed for windows, else we have the 'The input line is too long` on windows OS
    LauncherJarPlugin
  )
  .settings(coverageExcludedPackages := "com.lnvortex.rpc;de.heikoseeberger.akkahttpupickle")

lazy val coordinatorConfig = project
  .in(file("coordinator/config"))
  .settings(CommonSettings.prodSettings: _*)
  .settings(libraryDependencies ++= Deps.config)
  .settings(name := "coordinator-config")
  .dependsOn(core)

lazy val coordinatorCli = project
  .in(file("coordinator/cli"))
  .settings(CommonSettings.prodSettings: _*)
  .settings(libraryDependencies ++= Deps.cli)
  .settings(name := "coordinator-cli")
  .dependsOn(coordinatorConfig)
  .settings(coverageExcludedPackages := "com.lnvortex.coordinator.cli")

lazy val coordinatorRpc = project
  .in(file("coordinator/rpc-server"))
  .settings(CommonSettings.appSettings: _*)
  .settings(CommonSettings.dockerSettings: _*)
  .settings(CommonSettings.dockerBuildxSettings: _*)
//  .settings(bashScriptExtraDefines ++= IO.readLines(
//    baseDirectory.value / "src" / "universal" / "coordinator-rpc-server-extra-startup-script.sh"))
//  .settings(jlinkIgnoreMissingDependency := CommonSettings.jlinkIgnore)
  .settings(libraryDependencies ++= Deps.coordinatorRpc)
  .dependsOn(server, coordinatorConfig)
  .enablePlugins(
    JavaAppPackaging,
//    JlinkPlugin,
    DockerPlugin,
    // needed for windows, else we have the 'The input line is too long` on windows OS
    LauncherJarPlugin
  )
  .settings(coverageExcludedPackages := "com.lnvortex.coordinator.rpc;de.heikoseeberger.akkahttpupickle")

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
  .settings(
    coverageExcludedPackages := "com.lnvortex.core.api;com.lnvortex.core.config")

lazy val coreTest = project
  .in(file("core-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "core-test", libraryDependencies ++= Deps.coreTest)
  .dependsOn(core)

lazy val client = project
  .in(file("client"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "client", libraryDependencies ++= Deps.client)
  .dependsOn(core)

lazy val clientTest = project
  .in(file("client-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "client-test")
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
  .settings(name := "server-test")
  .settings(parallelExecution := false)
  .dependsOn(server, testkit)

lazy val clientServerTest = project
  .in(file("client-server-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "client-server-test")
  .settings(parallelExecution := false)
  .dependsOn(client, server, testkit)

lazy val testkit = project
  .in(file("testkit"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "testkit", libraryDependencies ++= Deps.testkit)
  .dependsOn(core, client, server, coreTest, lnd, clightning, bitcoind)

lazy val develop = project
  .in(file("develop"))
  .settings(CommonSettings.prodSettings: _*)
  .settings(name := "develop", libraryDependencies ++= Deps.develop)
  .dependsOn(coordinatorRpc, rpcServer, testkit)

commands += Command.command("testWallets") { state =>
  "coverage" ::
    "lndTest/test" ::
    "lnd/coverageAggregate" ::
    "lnd/coveralls" ::
    "clightningTest/test" ::
    "clightning/coverageAggregate" ::
    "clightning/coveralls" ::
    "bitcoindTest/test" ::
    "bitcoind/coverageAggregate" ::
    "bitcoind/coveralls" ::
    state
}

commands += Command.command("testClientServer") { state =>
  "coverage" ::
    "coreTest/test" ::
    "core/coverageAggregate" ::
    "core/coveralls" ::
    "configTest/test" ::
    "clientTest/test" ::
    "serverTest/test" ::
    "clientServerTest/test" ::
    "client/coverageAggregate" ::
    "client/coveralls" ::
    "server/coverageAggregate" ::
    "server/coveralls" ::
    state
}

TaskKeys.downloadLnd := {
  val logger = streams.value.log
  import scala.sys.process._

  val binaryDir = CommonSettings.binariesPath.resolve("lnd")

  if (Files.notExists(binaryDir)) {
    logger.info(s"Creating directory for lnd binaries: $binaryDir")
    Files.createDirectories(binaryDir)
  }

  val version = "0.15.4-beta"

  val (platform, suffix) =
    if (Properties.isLinux) ("linux-amd64", "tar.gz")
    else if (Properties.isMac && System.getProperty("os.arch") == "aarch64")
      ("darwin-arm64", "tar.gz")
    else if (Properties.isMac) ("darwin-amd64", "tar.gz")
    else if (Properties.isWin) ("windows-amd64", "zip")
    else sys.error(s"Unsupported OS: ${Properties.osName}")

  logger.debug(s"(Maybe) downloading lnd binaries for version: $version")

  val versionDir = binaryDir resolve s"lnd-$platform-v$version"
  val location =
    s"https://github.com/lightningnetwork/lnd/releases/download/v$version/lnd-$platform-v$version.$suffix"

  if (Files.exists(versionDir)) {
    logger.debug(
      s"Directory $versionDir already exists, skipping download of lnd $version")
  } else {
    val archiveLocation = binaryDir resolve s"$version.$suffix"
    logger.info(s"Downloading lnd version $version from location: $location")
    logger.info(s"Placing the file in $archiveLocation")
    val downloadCommand = url(location) #> archiveLocation.toFile
    downloadCommand.!!

    val bytes = Files.readAllBytes(archiveLocation)
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x" format _)
      .mkString

    val expectedHash =
      if (Properties.isLinux)
        "60f419568afc06210e28c40361d9770f1495ed85a9da68da5309f2ffa2a5c016"
      else if (Properties.isMac && System.getProperty("os.arch") == "aarch64")
        "30bdfa85d45bb973710534fa5a27bb881603d5f7c717d4e28ce7feaa7797124b"
      else if (Properties.isMac)
        "fa2141a86f5f61b8ab20a8471f5f62986641d2d30833a3e7bdfaeba1ace28001"
      else if (Properties.isWin)
        "3c21ab4bdc5e5a3d907ede875617993250cc426e2da077fb40a1caf71694d46e"
      else sys.error(s"Unsupported OS: ${Properties.osName}")

    val success = hash.equalsIgnoreCase(expectedHash)
    if (success) {
      logger.info(s"Download complete and verified, unzipping result")

      val extractCommand = s"tar -xzf $archiveLocation --directory $binaryDir"
      logger.info(s"Extracting archive with command: $extractCommand")
      extractCommand.!!
    } else {
      logger.error(
        s"Downloaded invalid version of lnd, got $hash, expected $expectedHash")
    }

    logger.info(s"Deleting archive")
    Files.delete(archiveLocation)

    if (!success) {
      throw new RuntimeException(s"Failed to download lnd v$version")
    }
  }
}

TaskKeys.downloadBitcoind := {
  val logger = streams.value.log
  import scala.sys.process._

  val binaryDir = CommonSettings.binariesPath.resolve("bitcoind")

  if (Files.notExists(binaryDir)) {
    logger.info(s"Creating directory for bitcoind binaries: $binaryDir")
    Files.createDirectories(binaryDir)
  }

  val versions =
    List("23.0", "22.0", "0.21.1", "0.20.1", "0.19.0.1", "0.18.1")

  logger.debug(
    s"(Maybe) downloading Bitcoin Core binaries for versions: ${versions.mkString(",")}")

  def getPlatformAndSuffix(version: String): (String, String) = {
    if (Properties.isLinux) ("x86_64-linux-gnu", "tar.gz")
    else if (Properties.isMac)
      version match {
        case "23.0" if System.getProperty("os.arch") == "aarch64" =>
          ("arm64-apple-darwin", "tar.gz")
        case "23.0" => ("x86_64-apple-darwin", "tar.gz")
        case _      => ("osx64", "tar.gz")
      }
    else if (Properties.isWin) ("win64", "zip")
    else sys.error(s"Unsupported OS: ${Properties.osName}")
  }

  implicit val ec: ExecutionContextExecutor =
    scala.concurrent.ExecutionContext.global
  val downloads = versions.map { version =>
    val (platform, suffix) = getPlatformAndSuffix(version)
    val archiveLocation = binaryDir resolve s"$version.$suffix"
    val location =
      if (version.init.endsWith("rc")) { // if it is a release candidate
        val (base, rc) = version.splitAt(version.length - 3)
        s"https://bitcoincore.org/bin/bitcoin-core-$base/test.$rc/bitcoin-$version-$platform.$suffix"
      } else
        s"https://bitcoincore.org/bin/bitcoin-core-$version/bitcoin-$version-$platform.$suffix"

    val expectedEndLocation = binaryDir resolve s"bitcoin-$version"

    if (
      Files
        .list(binaryDir)
        .iterator
        .asScala
        .map(_.toString)
        .exists(expectedEndLocation.toString.startsWith(_))
    ) {
      logger.debug(
        s"Directory $expectedEndLocation already exists, skipping download of version $version")
      Future.unit
    } else {
      // copy of FutureUtil.makeAsync
      def makeAsync(func: () => Unit): Future[Unit] = {
        val resultP = Promise[Unit]()

        ec.execute { () =>
          val result: Unit = func()
          resultP.success(result)
        }

        resultP.future
      }

      makeAsync { () =>
        logger.info(
          s"Downloading bitcoind version $version from location: $location")
        logger.info(s"Placing the file in $archiveLocation")
        val downloadCommand = url(location) #> archiveLocation.toFile
        downloadCommand.!!

        val bytes = Files.readAllBytes(archiveLocation)
        val hash = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map("%02x" format _)
          .mkString

        val expectedHash =
          if (Properties.isLinux)
            Map(
              "23.0" -> "2cca490c1f2842884a3c5b0606f179f9f937177da4eadd628e3f7fd7e25d26d0",
              "22.0" -> "59ebd25dd82a51638b7a6bb914586201e67db67b919b2a1ff08925a7936d1b16",
              "0.21.1" -> "366eb44a7a0aa5bd342deea215ec19a184a11f2ca22220304ebb20b9c8917e2b",
              "0.20.1" -> "376194f06596ecfa40331167c39bc70c355f960280bd2a645fdbf18f66527397",
              "0.19.0.1" -> "732cc96ae2e5e25603edf76b8c8af976fe518dd925f7e674710c6c8ee5189204",
              "0.18.1" -> "600d1db5e751fa85903e935a01a74f5cc57e1e7473c15fd3e17ed21e202cfe5a"
            )
          else if (Properties.isMac)
            Map(
              "23.0" -> (if (System.getProperty("os.arch") == "aarch64")
                           "7c8bc63731aa872b7b334a8a7d96e33536ad77d49029bad179b09dca32cd77ac"
                         else
                           "c816780583009a9dad426dc0c183c89be9da98906e1e2c7ebae91041c1aaaaf3"),
              "22.0" -> "2744d199c3343b2d94faffdfb2c94d75a630ba27301a70e47b0ad30a7e0155e9",
              "0.21.1" -> "1ea5cedb64318e9868a66d3ab65de14516f9ada53143e460d50af428b5aec3c7",
              "0.20.1" -> "b9024dde373ea7dad707363e07ec7e265383204127539ae0c234bff3a61da0d1",
              "0.19.0.1" -> "a64e4174e400f3a389abd76f4d6b1853788730013ab1dedc0e64b0a0025a0923",
              "0.18.1" -> "b7bbcee7a7540f711b171d6981f939ca8482005fde22689bc016596d80548bb1"
            )
          else if (Properties.isWin)
            Map(
              "23.0" -> "004b2e25b21e0f14cbcce6acec37f221447abbb3ea7931c689e508054bfc6cf6",
              "22.0" -> "9485e4b52ed6cebfe474ab4d7d0c1be6d0bb879ba7246a8239326b2230a77eb1",
              "0.21.1" -> "94c80f90184cdc7e7e75988a55b38384de262336abd80b1b30121c6e965dc74e",
              "0.20.1" -> "e59fba67afce011d32b5d723a3a0be12da1b8a34f5d7966e504520c48d64716d",
              "0.19.0.1" -> "7706593de727d893e4b1e750dc296ea682ccee79acdd08bbc81eaacf3b3173cf",
              "0.18.1" -> "b0f94ab43c068bac9c10a59cb3f1b595817256a00b84f0b724f8504b44e1314f"
            )
          else sys.error(s"Unsupported OS: ${Properties.osName}")

        val success = hash.equalsIgnoreCase(expectedHash(version))
        if (success) {
          logger.info(s"Download complete and verified, unzipping result")

          val extractCommand =
            s"tar -xzf $archiveLocation --directory $binaryDir"
          logger.info(s"Extracting archive with command: $extractCommand")
          extractCommand.!!
        } else {
          logger.error(
            s"Downloaded invalid version of bitcoind v$version, got $hash, expected ${expectedHash(version)}")
        }

        logger.info(s"Deleting archive")
        Files.delete(archiveLocation)

        if (!success)
          throw new RuntimeException(s"Failed to download bitcoind v$version")
      }

    }
  }

  // timeout if we cannot download in 5 minutes
  Await.result(Future.sequence(downloads), 5.minutes)
}

TaskKeys.downloadCLightning := {
  val logger = streams.value.log
  import scala.sys.process._

  val binaryDir = CommonSettings.binariesPath.resolve("clightning")

  if (Files.notExists(binaryDir)) {
    logger.info(s"Creating directory for clightning binaries: $binaryDir")
    Files.createDirectories(binaryDir)
  }

  val version = "0.10.2"

  val (platform, suffix) =
    if (Properties.isLinux) ("Ubuntu-20.04", "tar.xz")
    //    else if (Properties.isMac) ("darwin-amd64", "tar.gz") // todo c-lightning adding in a future release
    else sys.error(s"Unsupported OS: ${Properties.osName}")

  logger.debug(s"(Maybe) downloading clightning binaries for version: $version")

  val versionDir = binaryDir resolve version
  val location =
    s"https://github.com/ElementsProject/lightning/releases/download/v$version/clightning-v$version-$platform.tar.xz"

  if (Files.exists(versionDir)) {
    logger.debug(
      s"Directory $versionDir already exists, skipping download of clightning $version")
  } else {
    Files.createDirectories(versionDir)
    val archiveLocation = binaryDir resolve s"$version.$suffix"
    logger.info(
      s"Downloading clightning version $version from location: $location")
    logger.info(s"Placing the file in $archiveLocation")
    val downloadCommand = url(location) #> archiveLocation.toFile
    downloadCommand.!!

    val bytes = Files.readAllBytes(archiveLocation)
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x" format _)
      .mkString

    val expectedHash =
      if (Properties.isLinux)
        "de61bb1dec0f656e192f896de7dcb08f4b07cf9c2bdaef8c78d860cd80ea6776"
      else sys.error(s"Unsupported OS: ${Properties.osName}")

    if (hash.equalsIgnoreCase(expectedHash)) {
      logger.info(s"Download complete and verified, unzipping result")

      val extractCommand = s"tar -xf $archiveLocation --directory $versionDir"
      logger.info(s"Extracting archive with command: $extractCommand")
      extractCommand.!!
    } else {
      sys.error(
        s"Downloaded invalid version of c-lightning, got $hash, expected $expectedHash")
    }

    logger.info(s"Deleting archive")
    Files.delete(archiveLocation)
  }
}
