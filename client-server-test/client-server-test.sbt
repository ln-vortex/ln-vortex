Test / test := (Test / test dependsOn {
  Projects.`ln-vortex` / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  Projects.`ln-vortex` / TaskKeys.downloadLnd
}).value

publish / skip := true
