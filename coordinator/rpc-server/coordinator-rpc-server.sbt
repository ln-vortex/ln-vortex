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

enablePlugins(DebianPlugin, JavaAppPackaging, NativeImagePlugin)
