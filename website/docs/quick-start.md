---
title: Quick Start Guide
id: quick-start
sidebar_position: 1
---

Vortex is your one-stop shop for Lightning privacy. Vortex is focused on providing a simple and easy way to use
Lightning privately and pushing forward the use of taproot and its applications.

## Prerequisites

### Wallet

Vortex needs a wallet to use for collaborative transactions. Currently, Vortex supports the following wallets:

- [LND](/docs/Backends/lnd)
- [CLN](/docs/Backends/cln)
- [Bitcoin Core](/docs/Backends/bitcoind)

LND is the most popular Lightning implementation, and it is the recommended implementation to use with Vortex as will
give you access to the most features.

## Part 1: Install Vortex

### Release Binaries

If you are a regular user and intend to use Vortex in production, we recommend using the binaries.

You can download the latest release binaries from
the [GitHub releases page](https://github.com/ln-vortex/ln-vortex/releases/latest).

It is recommended to verify the binaries before using them. You can find the instructions for verifying the
binaries [here](/docs/verifying-binaries).

### Docker

Docker images are available on [Docker Hub](https://hub.docker.com/r/lnvortex/vortexd). You can also check out the
example [docker-compose](https://github.com/ln-vortex/ln-vortex/blob/master/docker-compose.yml) file if you are looking
to add Vortex to your existing docker-compose setup.

Docker images are built automatically for every commit to the `master` branch. You can find the latest master image with
the `master` tag, if you need to pin to a specific commit they are all tagged with an individual version as well. If you
are looking for the latest stable release, you can use the `latest` tag.

You can also find a docker image for the vortex gui [here](https://hub.docker.com/r/lnvortex/vortex-gui).

### Build from Source

If you do not want to use the binaries, you can build Vortex from source.

To build Vortex, you will need to have the following installed:

- [Java](https://adoptopenjdk.net/)
- [sbt](https://www.scala-sbt.org/)

Once you have those installed, you can build Vortex by running the following commands:

```bash
sbt rpcServer/universal:packageBin
```

This will create a vortexd binary in `./app/rpc-server/target/universal/scripts/bin` that you can run.

If you would just like to run the daemon, you can run the following command:

```bash
sbt rpcServer/run
```

## Part 2: Configure Vortex

First you will need to configure your wallet and vortex to connect to each other. You can find the configuration guide
for each available wallet [here](/docs/Backends).

Afterwards, you will need to configure Vortex. You can find the configuration guide [here](/docs/Configuration).

## Part 3: Run Vortex

Now that we have Vortex installed and configured with its wallet backend we may start it for the first time.

You may start vortex by simply using the command `vortexd`. Depending on our installation, we might have to specify the
location or add it to our path.

```bash
vortexd
```

Vortex will run in the background, you can use vortex-cli to interact with it. `vortex-cli` will pass our commands
to `vortexd` and return useful information back to us.

```bash
vortex-cli
```

You can also use the vortex gui to interact with vortexd. You can find the instructions for installing the vortex
gui [here](https://github.com/ln-vortex/ln-vortex-gui).
