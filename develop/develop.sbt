val downloadBitcoind = Projects.root / TaskKeys.downloadBitcoind

val downloadLnd = Projects.root / TaskKeys.downloadLnd

Compile / compile := (Compile / compile)
  .dependsOn(downloadBitcoind, downloadLnd)
  .value

run / fork := true

mainClass := Some("com.lnvortex.develop.CreateLocalDevEnvironment")
