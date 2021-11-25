import sbt._

object TaskKeys {

  lazy val downloadBitcoind = taskKey[Unit] {
    "Download bitcoind binaries, extract to ./binaries/bitcoind"
  }

  // will use when eclair has psbt funding support
  lazy val downloadEclair = taskKey[Unit] {
    "Download Eclair binaries, extract ./binaries/eclair"
  }

  lazy val downloadLnd = taskKey[Unit] {
    "Download lnd binaries, extract ./binaries/lnd"
  }

  lazy val downloadCLightning = taskKey[Unit] {
    "Download clightning binaries, extract ./binaries/clightning"
  }
}
