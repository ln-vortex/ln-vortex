import com.typesafe.sbt.packager.windows._
import sbt.Resolver
import java.nio.file.Files
import java.security.MessageDigest
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Properties

name := "LnVortex"

version := "1.0"

scalaVersion := "2.13.8"

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
    testkit
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
    testkit
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

lazy val rpcServer = project
  .in(file("app/rpc-server"))
  .settings(CommonSettings.prodSettings: _*)
  .settings(libraryDependencies ++= Deps.rpcServer)
  .settings(name := "vortexd")
  .dependsOn(client, lnd, clightning, config)

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

lazy val coordinatorRpc = project
  .in(file("coordinator/rpc-server"))
  .settings(CommonSettings.prodSettings: _*)
  .settings(libraryDependencies ++= Deps.rpcServer)
  .settings(name := "coordinator-rpc-server")
  .dependsOn(server, coordinatorConfig)

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

TaskKeys.downloadLnd := {
  val logger = streams.value.log
  import scala.sys.process._

  val binaryDir = CommonSettings.binariesPath.resolve("lnd")

  if (Files.notExists(binaryDir)) {
    logger.info(s"Creating directory for lnd binaries: $binaryDir")
    Files.createDirectories(binaryDir)
  }

  val version = "0.15.1-beta"

  val (platform, suffix) =
    if (Properties.isLinux) ("linux-amd64", "tar.gz")
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
        "0673768e657ac004367d07c20395d544a3d1df926be1a1990a17e23a8a91d4fb"
      else if (Properties.isMac)
        "7863c1f5374c854536ea6803baa46faeb4a09f1df5836746d1232c37b0fd9cf9"
      else if (Properties.isWin)
        "07d4a5147ec56a3775ecc8d3486f8bc3c04b53b47ef48e8258b4e6f8def06edf"
      else sys.error(s"Unsupported OS: ${Properties.osName}")

    if (hash.equalsIgnoreCase(expectedHash)) {
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

  val experimentalVersion = "0.18.99"
  val versions = List("23.0")

  logger.debug(
    s"(Maybe) downloading Bitcoin Core binaries for versions: ${versions.mkString(",")}")

  def getPlatformAndSuffix(version: String): (String, String) = {
    if (Properties.isLinux) ("x86_64-linux-gnu", "tar.gz")
    else if (Properties.isMac)
      version match {
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
      if (version == experimentalVersion)
        s"https://s3-us-west-1.amazonaws.com/suredbits.com/bitcoin-core-$version/bitcoin-$version-$platform.$suffix"
      else if (version.init.endsWith("rc")) { // if it is a release candidate
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
              "0.18.1" -> "600d1db5e751fa85903e935a01a74f5cc57e1e7473c15fd3e17ed21e202cfe5a",
              "0.17.0.1" -> "6ccc675ee91522eee5785457e922d8a155e4eb7d5524bd130eb0ef0f0c4a6008",
              "0.16.3" -> "5d422a9d544742bc0df12427383f9c2517433ce7b58cf672b9a9b17c2ef51e4f",
              experimentalVersion -> "f8b1a0ded648249e5e8c14fca3e11a733da8172b05523922c87557ea5eaaa4c5"
            )
          else if (Properties.isMac)
            Map(
              "23.0" -> "c816780583009a9dad426dc0c183c89be9da98906e1e2c7ebae91041c1aaaaf3",
              "22.0" -> "2744d199c3343b2d94faffdfb2c94d75a630ba27301a70e47b0ad30a7e0155e9",
              "0.21.1" -> "1ea5cedb64318e9868a66d3ab65de14516f9ada53143e460d50af428b5aec3c7",
              "0.20.1" -> "b9024dde373ea7dad707363e07ec7e265383204127539ae0c234bff3a61da0d1",
              "0.19.0.1" -> "a64e4174e400f3a389abd76f4d6b1853788730013ab1dedc0e64b0a0025a0923",
              "0.18.1" -> "b7bbcee7a7540f711b171d6981f939ca8482005fde22689bc016596d80548bb1",
              "0.17.0.1" -> "3b1fb3dd596edb656bbc0c11630392e201c1a4483a0e1a9f5dd22b6556cbae12",
              "0.16.3" -> "78c3bff3b619a19aed575961ea43cc9e142959218835cf51aede7f0b764fc25d",
              experimentalVersion -> "cfd4ed0b8db08fb1355aca44ca282b1de31e83b5862efaac527e3952b0987e55"
            )
          else if (Properties.isWin)
            Map(
              "23.0" -> "004b2e25b21e0f14cbcce6acec37f221447abbb3ea7931c689e508054bfc6cf6",
              "22.0" -> "9485e4b52ed6cebfe474ab4d7d0c1be6d0bb879ba7246a8239326b2230a77eb1",
              "0.21.1" -> "94c80f90184cdc7e7e75988a55b38384de262336abd80b1b30121c6e965dc74e",
              "0.20.1" -> "e59fba67afce011d32b5d723a3a0be12da1b8a34f5d7966e504520c48d64716d",
              "0.19.0.1" -> "7706593de727d893e4b1e750dc296ea682ccee79acdd08bbc81eaacf3b3173cf",
              "0.18.1" -> "b0f94ab43c068bac9c10a59cb3f1b595817256a00b84f0b724f8504b44e1314f",
              "0.17.0.1" -> "2d0a0aafe5a963beb965b7645f70f973a17f4fa4ddf245b61d532f2a58449f3e",
              "0.16.3" -> "52469c56222c1b5344065ef2d3ce6fc58ae42939a7b80643a7e3ee75ec237da9",
              experimentalVersion -> "b7ad8e6c0b91adf820499bf891f22f590e969b834980d4910efac5b082d83c49"
            )
          else sys.error(s"Unsupported OS: ${Properties.osName}")

        if (hash.equalsIgnoreCase(expectedHash(version))) {
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
      logger.error(
        s"Downloaded invalid version of c-lightning, got $hash, expected $expectedHash")
    }

    logger.info(s"Deleting archive")
    Files.delete(archiveLocation)
  }
}
