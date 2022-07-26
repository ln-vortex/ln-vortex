Test / test := (Test / test dependsOn {
  Projects.root / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  Projects.root / TaskKeys.downloadCLightning
}).value

publish / skip := true
