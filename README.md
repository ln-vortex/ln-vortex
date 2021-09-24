# LnVortex

[![Build Status](https://github.com/benthecarman/ln-vortex/actions/workflows/compile.yml/badge.svg)](https://github.com/benthecarman/ln-vortex/actions)
[![Tests Passing](https://github.com/benthecarman/ln-vortex/actions/workflows/test.yml/badge.svg)](https://github.com/benthecarman/ln-vortex/actions)

LnVortex is a tool to allow users to open lightning channels in a coinjoin when
using [lnd](https://github.com/lightningnetwork/lnd).

## Compatibility

LnVortex is compatible with `lnd` version v0.14.0-beta.

## Building from source

To get started you will need Java, Scala, and some other nice tools installed, luckily the Scala team has an easy setup
process!

Simply follow the instructions in [this short blog](https://www.scala-lang.org/2020/06/29/one-click-install.html) to get
started.

After having these installed you simply need to clone the repo, then run with `sbt gui/run`.

### macOS install

```
brew install scala
brew install sbt
```
