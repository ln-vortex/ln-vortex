---
title: LND
id: lnd
---

Lnd has first class support in Vortex. Lnd allows you to access every feature of Vortex and is the recommended backend
for most users. Vortex is compatible with lnd version v0.15.2-beta and above. It is recommended to use the latest
version of lnd as this will have the most up-to-date security fixes and what vortex is tested against.

If you are looking for a guide on how to set up lnd, please see
the [lnd documentation](https://docs.lightning.engineering/lightning-network-tools/lnd/run-lnd)

## Supported Features

- Lightning channel opens ⚡
- Taproot 🥕

## Configuration

First in your `vortex.conf` file you'll need to set the `lightningImplementation` to `lnd`:

```toml
vortex.lightningImplementation = "lnd"
```

Then you'll need to configure the `lnd` backend. The `lnd` backend has the following configuration options:

```toml
vortex.lnd.datadir = "/home/user/.lnd"
vortex.lnd.lndRpcUri = "127.0.0.1:10009"
```

By default, Vortex will use the default `lnd` datadir and rpc uri. If you are using a custom `lnd` datadir or rpc uri,
you will need to set these values in your `vortex.conf` file.
