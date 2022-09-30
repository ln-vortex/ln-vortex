---
title: Core Lightning
id: cln
---

Core Lightning is supported in Vortex. As of now, CLN is not fully compatible as it does not have taproot support.
Vortex supports CLN version 0.10.2 using the json-rpc.

If you are looking for a guide on how to set up cln, please see [their documentation](https://lightning.readthedocs.io/)

## Supported Features

- Lightning channel opens ⚡

## Configuration

First in your `vortex.conf` file you'll need to set the `lightningImplementation` to `cln`:

```toml
vortex.lightningImplementation = "cln"
```

Then you'll need to configure the `cln` backend. The `cln` backend has the following configuration options:

```toml
vortex.cln.datadir = "/home/user/.lightning"
vortex.cln.rpcuser = "rpcuser"
```

By default, Vortex will use the default `cln` datadir. If you are using a custom `cln` datadir you will need to set
these values in your `vortex.conf` file.
