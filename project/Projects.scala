import sbt._

object Projects {
  val core = project in file("..") / "core"
  val clightning = project in file("..") / "clightning"
}
