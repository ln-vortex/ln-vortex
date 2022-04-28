# API Docs

LnVortex runs a JSON RPC server. For this:

- All responses return a json object containing a `result` or an `error`, these can both be json objects.
- All requests must have an id that is either a `string` or a `number`
- All requests must have a `method` that is a `string`
- When a request has parameters it must have a `params` field that is a json object.

Example request:

```json
{
  "id": "3d00426c-4cb0-4a01-b360-6e50a2b39fc6",
  "method": "queuecoins",
  "params": {
    "outpoints": ["bc464504d430a3031f31a2653a253d174fa572963d76a9cd0002dce7f319fcbf:0"],
    "nodeId": "02f7467f4de732f3b3cffc8d5e007aecdf6e58878edb6e46a8e80164421c1b90aa",
    "peerAddr": "fypalpg6rmhmrfxhaupwsup6ukonzluu3afbe4mzuzv2rr32h4zrsgyd.onion:9735"
  }
}
```

RPC calls all must have a basic Authorization header, you can read more [here](https://swagger.io/docs/specification/authentication/basic-authentication/)

## Methods

### Get Info

method: `getinfo`

#### Params

None

#### Response

- network: String - [MainNet, TestNet3, RegTest, SigNet]


### List UTXOs

method: `listutxos`

#### Params

None

#### Response

list of utxos:

- address: String - bitcoin address
- amount: Number - in satoshis
- outPoint: String - The transaction outpoint in format `txid:vout`
- confirmed: Boolean - if the transaction is confirmed or not

### Get Balance

method: `getbalance`

#### Params

None

#### Response

balance in satoshis

### List Transactions

method: `listtransactions`

#### Params

None

#### Response

list of transactions:

- txId: String
- tx: String - Transaction in network serialization
- numConfirmations: Number
- blockHeight: Number
- label: String

### List Channels

method: `listchannels`

#### Params

None

#### Response

list of channels:

- remotePubkey: String
- shortChannelId: String
- public: Boolean - If this is a public or private channel
- amount: Number - Size of channel in satoshis
- active: Boolean - if the channel is currently active

### Queue Coins

method: `queuecoins`

#### Params

- outpoints: Array[String] - Outpoints should be in the format `txid:vout`
- nodeId: String - The pubkey of the node to open the channel to
- peerAddr: String - Optional, IP or onion address of the peer

#### Response

null
