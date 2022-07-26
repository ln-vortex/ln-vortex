import sbt._

object Projects {
  val root = project in file("..")
  val core = project in file("..") / "core"
  val clightning = project in file("..") / "clightning"
  val lnd = project in file("..") / "lnd"
  val bitcoind = project in file("..") / "bitcoind"
}
