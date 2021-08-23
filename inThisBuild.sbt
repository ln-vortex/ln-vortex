import scala.util.Properties

ThisBuild / scalafmtOnCompile := !Properties.envOrNone("CI").contains("true")
