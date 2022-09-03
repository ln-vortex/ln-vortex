// these two imports are needed for sbt syntax to work

import com.typesafe.sbt.SbtNativePackager.Docker
import com.typesafe.sbt.SbtNativePackager.autoImport.packageName
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerBaseImage
import sbt.Keys._
import sbt.{Def, _}
import sbtassembly.AssemblyKeys._
import sbtprotoc.ProtocPlugin.autoImport.PB
import sbtdynver.DynVer

import java.nio.file.{Path, Paths}
import scala.sys.process.Process
import scala.util.Properties

object CommonSettings {

  lazy val settings: Vector[Setting[_]] = Vector(
    version := "0.1.0",
    scalaVersion := "2.13.8",
    organization := "com.lnvortex",
    homepage := Some(url("https://github.com/benthecarman/ln-vortex")),
    maintainer.withRank(
      KeyRanks.Invisible) := "benthecarman <benthecarman@live.com>",
    developers := List(
      Developer(
        "benthecarman",
        "Ben Carman",
        "benthecarman@live.com",
        url("https://twitter.com/benthecarman")
      )
    ),
    Compile / scalacOptions ++= compilerOpts(scalaVersion = scalaVersion.value),
    Test / scalacOptions ++= testCompilerOpts(scalaVersion =
      scalaVersion.value),
    // remove annoying import unused things in the scala console
    // https://stackoverflow.com/questions/26940253/in-sbt-how-do-you-override-scalacoptions-for-console-in-all-configurations
    Compile / console / scalacOptions ~= (_ filterNot (s =>
      s == "-Ywarn-unused-import"
        || s == "-Ywarn-unused"
        || s == "-Xfatal-warnings"
        // for 2.13 -- they use different compiler opts
        || s == "-Xlint:unused")),
    // we don't want -Xfatal-warnings for publishing with publish/publishLocal either
//    Compile / doc / scalacOptions ~= (_ filterNot (s =>
//      s == "-Xfatal-warnings")),
    // silence all scaladoc warnings generated from invalid syntax
    // see: https://github.com/bitcoin-s/bitcoin-s/issues/3232
//    Compile / doc / scalacOptions ++= Vector(s"-Wconf:any:ws"),
    Test / console / scalacOptions ++= (Compile / console / scalacOptions).value,
    Test / scalacOptions ++= testCompilerOpts(scalaVersion.value),
    licenses += ("MIT", url("https://opensource.org/licenses/MIT")),
    // you need to build protoc manually to get it working on the new
    // mac m1 chip. For instructions on how to do so see
    // see: https://github.com/scalapb/ScalaPB/issues/1024
    PB.protocExecutable := (
      if (protocbridge.SystemDetector.detectedClassifier() == "osx-aarch_64")
        file(
          "/usr/local/bin/protoc"
        ) // to change if needed, this is where protobuf manual compilation put it for me
      else
        PB.protocExecutable.value
    ),
    assembly / test := {},
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers +=
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  )

  lazy val jvmSettings: Seq[Setting[_]] = List(
    ////
    // scaladoc settings
    Compile / doc / scalacOptions ++= List(
      "-doc-title",
      "ln-vortex",
      "-doc-version",
      version.value
    ),
    // Set apiURL to define the base URL for the Scaladocs for our library.
    // This will enable clients of our library to automatically link against
    // the API documentation using autoAPIMappings.
    apiURL := homepage.value.map(_.toString + "/api").map(url),
    // scaladoc settings end
    ////
    Compile / compile / javacOptions ++= {
      // https://github.com/eclipse/jetty.project/issues/3244#issuecomment-495322586
      Seq("--release", "8")
    }
  )

  private val commonCompilerOpts = {
    List(
      // https://stackoverflow.com/a/43103038/967713
      "-release",
      "8"
    )
  }

  /** Linting options for scalac */
  private val scala2_13CompilerLinting = {
    Seq(
      "-Xlint:unused",
      "-Xlint:adapted-args",
      "-Xlint:nullary-unit",
      "-Xlint:inaccessible",
      "-Xlint:infer-any",
      "-Xlint:missing-interpolator",
      "-Xlint:eta-sam"
    )
  }

  /** Compiler options for source code */
  private val scala2_13SourceCompilerOpts = {
    Seq("-Xfatal-warnings") ++ scala2_13CompilerLinting
  }

  private val nonScala2_13CompilerOpts = Seq(
    "-Xmax-classfile-name",
    "128",
    "-Ywarn-unused",
    "-Ywarn-unused-import"
  )

  // https://docs.scala-lang.org/overviews/compiler-options/index.html
  def compilerOpts(scalaVersion: String): Seq[String] = {
    Seq(
      "-unchecked",
      "-feature",
      "-deprecation",
      "-Ywarn-dead-code",
      "-Ywarn-value-discard",
      "-Ywarn-unused",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Ypatmat-exhaust-depth",
      "off"
    ) ++ commonCompilerOpts ++ {
      if (scalaVersion.startsWith("2.13")) {
        scala2_13SourceCompilerOpts
      } else nonScala2_13CompilerOpts
    }
  }

  def testCompilerOpts(scalaVersion: String): Seq[String] = {
    (commonCompilerOpts ++
      // initialization checks: https://docs.scala-lang.org/tutorials/FAQ/initialization-order.html
      Vector("-Xcheckinit") ++
      compilerOpts(scalaVersion))
      .filterNot(_ == "-Xfatal-warnings")
  }

  lazy val testSettings: Seq[Setting[_]] = Seq(
    // show full stack trace (-oF) of failed tests and duration of tests (-oD)
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    Test / logBuffered := false,
    skip / publish := true
  ) ++ settings

  lazy val prodSettings: Seq[Setting[_]] = settings

  lazy val appSettings: Seq[Setting[_]] = prodSettings ++ Vector(
    // gives us the 'universal' directory in build artifacts
    Compile / unmanagedResourceDirectories += baseDirectory.value / "src" / "universal"
  )

  lazy val dockerSettings: Seq[Setting[_]] = {
    Vector(
      // https://sbt-native-packager.readthedocs.io/en/latest/formats/docker.html
      dockerBaseImage := "eclipse-temurin:17",
      dockerRepository := Some("lnvortex"),
      Docker / daemonUser := "ln-vortex",
      // needed for umbrel environment, container uids and host uids must matchup so we can
      // properly write to volumes on the host machine
      // see: https://medium.com/@mccode/understanding-how-uid-and-gid-work-in-docker-containers-c37a01d01cf
      // Docker / daemonUserUid := Some("1000"),
      Docker / packageName := packageName.value,
      Docker / version := version.value,
      // add a default exposed volume of /ln-vortex so we can always write data here
      dockerExposedVolumes += "/ln-vortex",
      dockerUpdateLatest := DynVer.isSnapshot
    )
  }

  // See https://softwaremill.com/how-to-build-multi-platform-docker-image-with-sbt-and-docker-buildx/
  lazy val ensureDockerBuildx =
    taskKey[Unit]("Ensure that docker buildx configuration exists")

  lazy val dockerBuildWithBuildx =
    taskKey[Unit]("Build docker images using buildx")

  /** These settings are needed to produce docker images across different chip
    * architectures such as amd64 and arm64
    *
    * @see
    *   https://softwaremill.com/how-to-build-multi-platform-docker-image-with-sbt-and-docker-buildx/
    */
  lazy val dockerBuildxSettings: Seq[Def.Setting[Task[Unit]]] = {
    Seq(
      ensureDockerBuildx := {
        if (Process("docker buildx inspect multi-arch-builder").! == 1) {
          Process("docker buildx create --use --name multi-arch-builder",
                  baseDirectory.value).!
        }
      },
      dockerBuildWithBuildx := {
        streams.value.log("Building and pushing image with Buildx")
        dockerAliases.value.foreach { alias =>
          // issue the command below in to the terminal in the same directory that
          // our sbt plugin generates the docker file.
          // if you want to reproduce the docker file, run docker:stage
          // in your sbt terminal and you should find it in target/docker/stage/
          val cmd =
            "docker buildx build --platform=linux/amd64,linux/arm64 --push -t " +
              alias + " ."
          val dockerFileDir =
            baseDirectory.value / "target" / "docker" / "stage"
          Process(cmd, dockerFileDir).!
        }
      },
      Docker / publish := Def
        .sequential(
          Docker / publishLocal,
          ensureDockerBuildx,
          dockerBuildWithBuildx
        )
        .value
    )
  }

  lazy val binariesPath: Path =
    Paths.get(Properties.userHome, ".bitcoin-s", "binaries")
}
