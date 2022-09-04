import com.typesafe.sbt.packager.docker.DockerChmodType

name := "vortex-coordinator"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", _ @_*)         => MergeStrategy.discard
  case PathList("reference.conf", _ @_*)   => MergeStrategy.concat
  case PathList("application.conf", _ @_*) => MergeStrategy.concat
  case "reference.conf"                    => MergeStrategy.concat
  case "application.conf"                  => MergeStrategy.concat
  case PathList("logback.xml", _ @_*)      => MergeStrategy.concat
  case PathList("logback-test.xml", _ @_*) => MergeStrategy.concat
  case _                                   => MergeStrategy.first
}

run / fork := true

mainClass := Some("com.lnvortex.coordinator.rpc.CoordinatorDaemon")

enablePlugins(DebianPlugin, JavaAppPackaging, NativeImagePlugin, DockerPlugin)

packageSummary := "Vortex Coordinator"

packageDescription := "Runs the Vortex coordinator daemon"

dockerExposedPorts ++= Seq(12522)

dockerEntrypoint := Seq("/opt/docker/bin/vortex-coordinator")

//so the server can be read and executed by all users
dockerAdditionalPermissions += (DockerChmodType.Custom(
  "a=rx"), "/opt/docker/bin/vortex-coordinator")

//this passes in our default configuration for docker
//you can override this by passing in a custom configuration
//when the docker container is started by using bind mount
//https://docs.docker.com/storage/bind-mounts/#start-a-container-with-a-bind-mount
dockerCmd ++= Seq("--conf", "/opt/docker/docker-application.conf")
