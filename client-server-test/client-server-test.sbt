Test / test := (Test / test dependsOn {
  Projects.root / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  Projects.root / TaskKeys.downloadLnd
}).value

publish / skip := true
