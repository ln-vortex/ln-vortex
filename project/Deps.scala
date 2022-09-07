import sbt._

object Deps {

  object V {
    val akkaV = "10.2.10"
    val akkaStreamV = "2.6.20"
    val akkaActorV: String = akkaStreamV

    val bitcoinsV = "1.9.3-17-018a6e58-SNAPSHOT"

    val scoptV = "4.1.0"

    val sttpV = "1.7.2"

    val microPickleV = "1.3.8"

    val grizzledSlf4jV = "1.3.4"
    val logback = "1.4.0"
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

    val sttp =
      "com.softwaremill.sttp" %% "core" % V.sttpV withSources () withJavadoc ()

    val micoPickle =
      "com.lihaoyi" %% "upickle" % V.microPickleV withSources () withJavadoc ()

    val scopt =
      "com.github.scopt" %% "scopt" % V.scoptV withSources () withJavadoc ()

    val grizzledSlf4j =
      "org.clapper" %% "grizzled-slf4j" % V.grizzledSlf4jV withSources () withJavadoc ()

    val logback =
      "ch.qos.logback" % "logback-classic" % V.logback withSources () withJavadoc ()

    val bitcoinsCore =
      "org.bitcoin-s" %% "bitcoin-s-core" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsKeyManager =
      "org.bitcoin-s" %% "bitcoin-s-key-manager" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTor =
      "org.bitcoin-s" %% "bitcoin-s-tor" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsLnd =
      "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsCLightning =
      "org.bitcoin-s" %% "bitcoin-s-clightning-rpc" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsBitcoindRpc =
      "org.bitcoin-s" %% "bitcoin-s-bitcoind-rpc" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTestkitCore =
      "org.bitcoin-s" %% "bitcoin-s-testkit-core" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTestkit =
      "org.bitcoin-s" %% "bitcoin-s-testkit" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsFeeProvider =
      "org.bitcoin-s" %% "bitcoin-s-fee-provider" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsAppCommons =
      "org.bitcoin-s" %% "bitcoin-s-app-commons" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsDbCommons =
      "org.bitcoin-s" %% "bitcoin-s-db-commons" % V.bitcoinsV withSources () withJavadoc ()
  }

  val config: List[ModuleID] = List(Compile.bitcoinsAppCommons)

  val cli: List[ModuleID] = List(
    Compile.sttp,
    Compile.micoPickle,
    Compile.scopt,
    Compile.logback
  )

  val rpcServer: List[ModuleID] =
    List(
      Compile.akkaHttp,
      Compile.akkaSlf4j,
      Compile.micoPickle,
      Compile.grizzledSlf4j
    )

  val core: List[ModuleID] = List(
    Compile.bitcoinsAppCommons,
    Compile.grizzledSlf4j
  )

  val lndBackend: List[ModuleID] =
    List(Compile.bitcoinsLnd, Compile.grizzledSlf4j)

  val bitcoindBackend: List[ModuleID] =
    List(Compile.bitcoinsBitcoindRpc, Compile.grizzledSlf4j)

  val cLightningBackend: List[ModuleID] =
    List(Compile.bitcoinsCLightning,
         Compile.akkaActor,
         Compile.bitcoinsCore,
         Compile.grizzledSlf4j)

  val client: List[ModuleID] = List(
    Compile.bitcoinsTor,
    Compile.bitcoinsDbCommons,
    Compile.akkaActor,
    Compile.akkaHttp,
    Compile.akkaStream,
    Compile.akkaSlf4j,
    Compile.grizzledSlf4j
  )

  val server: List[ModuleID] =
    List(
      Compile.bitcoinsKeyManager,
      Compile.bitcoinsFeeProvider,
      Compile.bitcoinsBitcoindRpc,
      Compile.bitcoinsTor,
      Compile.bitcoinsDbCommons,
      Compile.akkaActor,
      Compile.akkaHttp,
      Compile.akkaStream,
      Compile.akkaSlf4j,
      Compile.grizzledSlf4j
    )

  val coreTest: List[ModuleID] = List(Compile.bitcoinsTestkitCore) ++ core

  val testkit: List[ModuleID] =
    List(Compile.bitcoinsTestkit, Compile.bitcoinsBitcoindRpc)
}
