Test / test := (Test / test dependsOn {
  Projects.`ln-vortex` / TaskKeys.downloadBitcoind
}).value

publish / skip := true
