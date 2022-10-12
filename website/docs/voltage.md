---
title: Setting up with Voltage
id: voltage
sidebar_position: 6
---

# Setting up with Voltage

Voltage is a tool that allows you to run a Lightning node in the cloud. It is a simple and easy way to get started with
Vortex.

To get started, you will need to create an account on [Voltage](https://voltage.cloud/). Once you have an account, you
can will need to set up a Lightning node and then download your TLS certificate and macaroon.

## Configuring Vortex

Once you have your TLS certificate and macaroon, you will need configure Vortex to use them. You will need to create
a `vortex.conf` file. The default place to put this file in on Linux is `~/.ln-vortex/vortex.conf`. On macOS, it
is `~/ln-vortex/vortex.conf`.

You will then need to add the following to your configuration file:

```
vortex {
    lightningImplementation = "lnd"
    lnd.tlsCert="/path/to/tls.cert"
    lnd.macaroonFile="/path/to/admin.macaroon"
    lnd.rpcUri="<Node API Endpoint>:10009"
}
```
