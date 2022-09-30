---
title: Bitcoin Core
id: bitcoind
---

Bitcoin Core is supported in Vortex. Bitcoin Core is not a lightning implementation, so you cannot use it for opening
lightning channels. However, you can use it to for taproot collaborative transactions! Vortex supports Bitcoin Core
version
v0.17 and up, however, taproot support is only available in Bitcoin Core version v22.0 and up.

## Supported Features

- Taproot 🥕

## Configuration

First in your `vortex.conf` file you'll need to set the `lightningImplementation` to `bitcoind`:

```toml
vortex.lightningImplementation = "bitcoind"
```

Then you'll need to configure the `bitcoind` backend. The `bitcoind` backend has the following configuration options:

```toml
vortex.bitcoind.datadir = "/home/user/.bitcoin"
vortex.bitcoind.rpcconnect = "localhost"
vortex.bitcoind.rpcport = "8332"
vortex.bitcoind.version = "23"
vortex.bitcoind.rpcuser = "rpcuser"
vortex.bitcoind.rpcpassword = "rpcpassword"
```

By default, Vortex will use the default `bitcoind` datadir and read your `bitcoin.conf` file. If you are using other
configuration options, you will need to set these values in your `vortex.conf` file.
