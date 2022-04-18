# LnVortex

LnVortex is a tool to allow users to open lightning channels in a coinjoin when
using [lnd](https://github.com/lightningnetwork/lnd) and [Core Lightning](https://github.com/ElementsProject/lightning)

## Compatibility

LnVortex is compatible with `lnd` version v0.14.2-beta and core lightning version v0.10.2.

## Building from source

### Scala/Java

You can choose to install the Scala toolchain with sdkman or coursier.

#### Sdkman

You can install sdkman [here](https://sdkman.io/install)

Next you can install `java` and `sbt` with

```
sdk install java # not always needed
sdk install sbt
```

#### Coursier

If you don't like `curl`, you can use OS specific package managers to install coursier [here](https://get-coursier.io/docs/2.0.0-RC2/cli-overview.html#installation)

> ln-vortex requires java9+ for development environments. If you do not have java9+ installed, you will not be able to build ln-vortex.
[You will run into this error if you are on java8 or lower](https://github.com/bitcoin-s/bitcoin-s/issues/3298)

If you follow the coursier route, [you can switch to a java11 version by running](https://get-coursier.io/docs/2.0.0-RC6-15/cli-java.html)

```
cs java --jvm adopt:11 --setup
```

### macOS install

```
brew install scala
brew install sbt
```

### Running the client

Running the client can simply be done by running

```
sbt rpcServer/run
```
