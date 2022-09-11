Test / test := (Test / test dependsOn {
  Projects.`ln-vortex` / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  Projects.`ln-vortex` / TaskKeys.downloadCLightning
}).value

publish / skip := true
