// these two imports are needed for sbt syntax to work

import com.typesafe.sbt.SbtNativePackager.Docker
import com.typesafe.sbt.SbtNativePackager.autoImport.packageName
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes.jlink.JlinkPlugin.autoImport.JlinkIgnore
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
    scalaVersion := "2.13.10",
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

  lazy val additionalDockerTag: Option[String] = sys.env.get("DOCKER_TAG")

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
      dockerUpdateLatest := isRelease,
      dockerAliases ++= additionalDockerTag
        .map(t => dockerAlias.value.withTag(Some(t)))
        .toSeq
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

  def buildPackageName(packageName: String): String = {
    val osName = getSimpleOSName
    val split = packageName.split("-")
    val versionIdx = split.zipWithIndex.find(_._1.count(_ == '.') > 1).get._2
    val insertedOSName = split.take(versionIdx) ++ Vector(osName)
    if (isRelease) {
      // bitcoin-s-server-linux-1.9.3-1-60bfd603-SNAPSHOT.zip -> bitcoin-s-server-linux-1.9.3.zip
      insertedOSName.mkString("-") ++ "-" ++ split(versionIdx)
    } else {
      // bitcoin-s-server-1.9.2-1-59aaf330-20220616-1614-SNAPSHOT -> bitcoin-s-server-linux-1.9.2-1-59aaf330-20220616-1614-SNAPSHOT
      // bitcoin-s-cli-1.9.2-1-59aaf330-20220616-1614-SNAPSHOT.zip -> bitcoin-s-cli-linux-1.9.2-1-59aaf330-20220616-1614-SNAPSHOT.zip
      (insertedOSName ++ split.drop(versionIdx)).mkString("-")
    }
  }

  /** @see https://github.com/sbt/sbt-dynver#detail */
  def isRelease: Boolean = {
    DynVer.isVersionStable && !DynVer.isSnapshot
  }

  private def getSimpleOSName: String = {
    if (Properties.isWin) {
      "windows"
    } else if (Properties.isMac) {
      "mac"
    } else if (Properties.isLinux) {
      "linux"
    } else {
      "unknown-os"
    }
  }

  lazy val binariesPath: Path =
    Paths.get(Properties.userHome, ".bitcoin-s", "binaries")

  // see https://www.scala-sbt.org/sbt-native-packager/archetypes/jlink_plugin.html?highlight=jlinkignore#jlink-plugin
  val jlinkIgnore = {
    val deps = Vector(
      // direct_dependency -> transitive dependency
      "com.zaxxer.hikari.hibernate" -> "org.hibernate.service.spi",
      "com.zaxxer.hikari.metrics.dropwizard" -> "com.codahale.metrics.health",
      "com.zaxxer.hikari.metrics.micrometer" -> "io.micrometer.core.instrument",
      "com.zaxxer.hikari.metrics.prometheus" -> "io.prometheus.client",
      "com.zaxxer.hikari.pool" -> "com.codahale.metrics.health",
      "com.zaxxer.hikari.pool" -> "io.micrometer.core.instrument",
      "com.zaxxer.hikari.util" -> "javassist",
      "com.zaxxer.hikari.util" -> "javassist.bytecode",
      "monix.execution.misc" -> "scala.tools.nsc",
      "org.flywaydb.core.api.configuration" -> "software.amazon.awssdk.services.s3",
      "org.flywaydb.core.internal.database.oracle" -> "oracle.jdbc",
      "org.flywaydb.core.internal.logging.apachecommons" -> "org.apache.commons.logging",
      "org.flywaydb.core.internal.logging.log4j2" -> "org.apache.logging.log4j",
      "org.flywaydb.core.internal.resource.s3" -> "software.amazon.awssdk.awscore.exception",
      "org.flywaydb.core.internal.resource.s3" -> "software.amazon.awssdk.core",
      "org.flywaydb.core.internal.resource.s3" -> "software.amazon.awssdk.services.s3",
      "org.flywaydb.core.internal.resource.s3" -> "software.amazon.awssdk.services.s3.model",
      "org.flywaydb.core.internal.scanner.classpath" -> "org.osgi.framework",
      "org.flywaydb.core.internal.scanner.classpath.jboss" -> "org.jboss.vfs",
      "org.flywaydb.core.internal.scanner.cloud.s3" -> "software.amazon.awssdk.core.exception",
      "org.flywaydb.core.internal.scanner.cloud.s3" -> "software.amazon.awssdk.services.s3",
      "org.flywaydb.core.internal.scanner.cloud.s3" -> "software.amazon.awssdk.services.s3.model",
      "org.flywaydb.core.internal.util" -> "com.google.gson",
      "org.flywaydb.core.internal.util" -> "com.google.gson.reflect",
      "org.postgresql.osgi" -> "org.osgi.framework",
      "org.postgresql.osgi" -> "org.osgi.service.jdbc",
      "org.postgresql.sspi" -> "com.sun.jna",
      "org.postgresql.sspi" -> "com.sun.jna.platform.win32",
      "org.postgresql.sspi" -> "com.sun.jna.ptr",
      "org.postgresql.sspi" -> "com.sun.jna.win32",
      "org.postgresql.sspi" -> "waffle.windows.auth",
      "org.postgresql.sspi" -> "waffle.windows.auth.impl",
      "scala.meta.internal.svm_subs" -> "com.oracle.svm.core.annotate",
      "shapeless" -> "scala.reflect.macros.contexts",
      "shapeless" -> "scala.tools.nsc",
      "shapeless" -> "scala.tools.nsc.ast",
      "shapeless" -> "scala.tools.nsc.typechecker",
      "akka.grpc.javadsl" -> "ch.megard.akka.http.cors.javadsl",
      "akka.grpc.javadsl" -> "ch.megard.akka.http.cors.javadsl.settings",
      "akka.grpc.javadsl" -> "ch.megard.akka.http.cors.scaladsl.settings",
      "akka.grpc.scaladsl" -> "ch.megard.akka.http.cors.scaladsl",
      "akka.grpc.scaladsl" -> "ch.megard.akka.http.cors.scaladsl.model",
      "akka.grpc.scaladsl" -> "ch.megard.akka.http.cors.scaladsl.settings",
      "ch.qos.logback.classic" -> "jakarta.servlet.http",
      "ch.qos.logback.classic.helpers" -> "jakarta.servlet",
      "ch.qos.logback.classic.helpers" -> "jakarta.servlet.http",
      "ch.qos.logback.classic.selector.servlet" -> "jakarta.servlet",
      "ch.qos.logback.classic.servlet" -> "jakarta.servlet",
      "ch.qos.logback.core.boolex" -> "org.codehaus.janino",
      "ch.qos.logback.core.joran.conditional" -> "org.codehaus.commons.compiler",
      "ch.qos.logback.core.joran.conditional" -> "org.codehaus.janino",
      "ch.qos.logback.core.net" -> "jakarta.mail",
      "ch.qos.logback.core.net" -> "jakarta.mail.internet",
      "ch.qos.logback.core.status" -> "jakarta.servlet",
      "ch.qos.logback.core.status" -> "jakarta.servlet.http",
      "com.zaxxer.hikari" -> "com.codahale.metrics.health",
      "com.zaxxer.hikari.hibernate" -> "org.hibernate",
      "com.zaxxer.hikari.hibernate" -> "org.hibernate.cfg",
      "com.zaxxer.hikari.hibernate" -> "org.hibernate.engine.jdbc.connections.spi",
      "com.zaxxer.hikari.hibernate" -> "org.hibernate.service",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.aayushatharva.brotli4j",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.aayushatharva.brotli4j.decoder",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.aayushatharva.brotli4j.encoder",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.github.luben.zstd",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.jcraft.jzlib",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.ning.compress",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.ning.compress.lzf",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "com.ning.compress.lzf.util",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "lzma.sdk",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "lzma.sdk.lzma",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "net.jpountz.lz4",
      "io.grpc.netty.shaded.io.netty.handler.codec.compression" -> "net.jpountz.xxhash",
      "io.grpc.netty.shaded.io.netty.handler.codec.http" -> "com.aayushatharva.brotli4j.encoder",
      "io.grpc.netty.shaded.io.netty.handler.codec.http2" -> "com.aayushatharva.brotli4j.encoder",
      "io.grpc.netty.shaded.io.netty.handler.codec.marshalling" -> "org.jboss.marshalling",
      "io.grpc.netty.shaded.io.netty.handler.codec.protobuf" -> "com.google.protobuf.nano",
      "io.grpc.netty.shaded.io.netty.handler.codec.spdy" -> "com.jcraft.jzlib",
      "io.grpc.netty.shaded.io.netty.handler.ssl" -> "org.conscrypt",
      "io.grpc.netty.shaded.io.netty.handler.ssl" -> "org.eclipse.jetty.alpn",
      "io.grpc.netty.shaded.io.netty.handler.ssl" -> "org.eclipse.jetty.npn",
      "io.grpc.netty.shaded.io.netty.handler.ssl.util" -> "org.bouncycastle.cert",
      "io.grpc.netty.shaded.io.netty.handler.ssl.util" -> "org.bouncycastle.cert.jcajce",
      "io.grpc.netty.shaded.io.netty.handler.ssl.util" -> "org.bouncycastle.operator",
      "io.grpc.netty.shaded.io.netty.handler.ssl.util" -> "org.bouncycastle.operator.jcajce",
      "io.grpc.netty.shaded.io.netty.util" -> "com.oracle.svm.core.annotate",
      "io.grpc.netty.shaded.io.netty.util.concurrent" -> "org.jetbrains.annotations",
      "io.grpc.netty.shaded.io.netty.util.internal" -> "reactor.blockhound",
      "io.grpc.netty.shaded.io.netty.util.internal" -> "reactor.blockhound.integration",
      "io.grpc.netty.shaded.io.netty.util.internal.logging" -> "org.apache.commons.logging",
      "io.grpc.netty.shaded.io.netty.util.internal.logging" -> "org.apache.log4j",
      "io.grpc.netty.shaded.io.netty.util.internal.logging" -> "org.apache.logging.log4j",
      "io.grpc.netty.shaded.io.netty.util.internal.logging" -> "org.apache.logging.log4j.message",
      "io.grpc.netty.shaded.io.netty.util.internal.logging" -> "org.apache.logging.log4j.spi",
      "io.grpc.netty.shaded.io.netty.util.internal.svm" -> "com.oracle.svm.core.annotate",
      "com.thoughtworks.paranamer" -> "javax.inject"
    )

    JlinkIgnore.byPackagePrefix(deps: _*)
  }
}
