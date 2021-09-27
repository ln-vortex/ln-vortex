import sbt._

object Deps {

  lazy val arch: String = System.getProperty("os.arch")

  lazy val osName: String = System.getProperty("os.name") match {
    case n if n.startsWith("Linux") => "linux"
    case n if n.startsWith("Mac") =>
      if (arch == "aarch64") {
        //needed to accommodate the different chip
        //arch for M1. see: https://github.com/bitcoin-s/bitcoin-s/pull/3041
        s"mac-$arch"
      } else {
        "mac"
      }
    case n if n.startsWith("Windows") => "win"
    case x =>
      throw new Exception(s"Unknown platform $x!")
  }

  object V {
    val akkaV = "10.2.6"
    val akkaStreamV = "2.6.16"
    val akkaActorV: String = akkaStreamV

    val scalaFxV = "16.0.0-R25"
    val javaFxV = "17-ea+8"
    // todo update when lnd v0.14.0-beta is in bitcoin-s
    val bitcoinsV = "1.7.0-180-cd327a78-SNAPSHOT"

    val grizzledSlf4jV = "1.3.4"
  }

  object Compile {

    val akkaHttp =
      "com.typesafe.akka" %% "akka-http" % V.akkaV withSources () withJavadoc ()

    val akkaStream =
      "com.typesafe.akka" %% "akka-stream" % V.akkaStreamV withSources () withJavadoc ()

    val akkaActor =
      "com.typesafe.akka" %% "akka-actor" % V.akkaStreamV withSources () withJavadoc ()

    val akkaSlf4j =
      "com.typesafe.akka" %% "akka-slf4j" % V.akkaStreamV withSources () withJavadoc ()

    val grizzledSlf4j =
      "org.clapper" %% "grizzled-slf4j" % V.grizzledSlf4jV withSources () withJavadoc ()

    val bitcoinsCore =
      "org.bitcoin-s" %% "bitcoin-s-core" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsKeyManager =
      "org.bitcoin-s" %% "bitcoin-s-key-manager" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTor =
      "org.bitcoin-s" %% "bitcoin-s-tor" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsLnd =
      "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTestkitCore =
      "org.bitcoin-s" %% "bitcoin-s-testkit-core" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTestkit =
      "org.bitcoin-s" %% "bitcoin-s-testkit" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsFeeProvider =
      "org.bitcoin-s" %% "bitcoin-s-fee-provider" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsDbCommons =
      "org.bitcoin-s" %% "bitcoin-s-db-commons" % V.bitcoinsV withSources () withJavadoc ()

    val scalaFx =
      "org.scalafx" %% "scalafx" % Deps.V.scalaFxV withSources () withJavadoc ()

    lazy val javaFxBase =
      "org.openjfx" % s"javafx-base" % V.javaFxV classifier osName withSources () withJavadoc ()

    lazy val javaFxControls =
      "org.openjfx" % s"javafx-controls" % V.javaFxV classifier osName withSources () withJavadoc ()

    lazy val javaFxFxml =
      "org.openjfx" % s"javafx-fxml" % V.javaFxV classifier osName withSources () withJavadoc ()

    lazy val javaFxGraphics =
      "org.openjfx" % s"javafx-graphics" % V.javaFxV classifier osName withSources () withJavadoc ()

    lazy val javaFxMedia =
      "org.openjfx" % s"javafx-media" % V.javaFxV classifier osName withSources () withJavadoc ()

    lazy val javaFxSwing =
      "org.openjfx" % s"javafx-swing" % V.javaFxV classifier osName withSources () withJavadoc ()

    lazy val javaFxWeb =
      "org.openjfx" % s"javafx-web" % V.javaFxV classifier osName withSources () withJavadoc ()

    lazy val javaFxDeps = List(javaFxBase,
                               javaFxControls,
                               javaFxFxml,
                               javaFxGraphics,
                               javaFxMedia,
                               javaFxSwing,
                               javaFxWeb)
  }

  val core: List[ModuleID] = List(
    Compile.bitcoinsCore,
    Compile.grizzledSlf4j
  )

  val backend: List[ModuleID] = List(
    Compile.bitcoinsTor,
    Compile.bitcoinsLnd,
    Compile.bitcoinsDbCommons,
    Compile.akkaActor,
    Compile.akkaHttp,
    Compile.akkaStream,
    Compile.akkaSlf4j,
    Compile.grizzledSlf4j
  )

  val server: List[ModuleID] =
    List(Compile.bitcoinsKeyManager, Compile.bitcoinsFeeProvider) ++ backend

  val coreTest: List[ModuleID] = List(Compile.bitcoinsTestkitCore) ++ core

  val clientServerTest: List[ModuleID] =
    List(Compile.bitcoinsTestkit) ++ backend

  val gui: List[ModuleID] =
    List(Compile.scalaFx, Compile.grizzledSlf4j) ++ Compile.javaFxDeps
}
