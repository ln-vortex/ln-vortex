// these two imports are needed for sbt syntax to work
import sbt.Keys._
import sbt._

object CommonSettings {

  lazy val settings: Seq[Setting[_]] = List(
    homepage := Some(url("https://github.com/benthecarman/ln-vortex")),
    developers := List(
      Developer(
        "benthecarman",
        "Ben Carman",
        "benthecarman@live.com",
        url("https://twitter.com/benthecarman")
      ))
  )
}
