Test / test := (Test / test dependsOn {
  Projects.root / TaskKeys.downloadBitcoind
}).value

publish / skip := true
