import sbt._

object TaskKeys {

  lazy val downloadCLightning = taskKey[Unit] {
    "Download clightning binaries, extract ./binaries/clightning"
  }
}
