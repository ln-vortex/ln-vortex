# API Docs

LnVortex runs a JSON RPC server. For this:

- All responses return a JSON object containing a `result` or an `error`.
- All requests must have an ID that is either a `string` or a `number`
- All requests must have a `method` that is a `string`
- When a request has parameters it must have a `params` field that is a json object.

Example request:

```json
{
  "id": "3d00426c-4cb0-4a01-b360-6e50a2b39fc6",
  "method": "queuecoins",
  "params": {
    "coordinator": "Lightning Testnet",
    "outpoints": [
      "bc464504d430a3031f31a2653a253d174fa572963d76a9cd0002dce7f319fcbf:0"
    ],
    "nodeId": "02f7467f4de732f3b3cffc8d5e007aecdf6e58878edb6e46a8e80164421c1b90aa",
    "peerAddr": "fypalpg6rmhmrfxhaupwsup6ukonzluu3afbe4mzuzv2rr32h4zrsgyd.onion:9735"
  }
}
```

Every RPC call must have a basic Authorization header, you can read
more [here](https://swagger.io/docs/specification/authentication/basic-authentication/).

## Methods

### Get Info

method: `getinfo`

#### Params

None

#### Response

- network: String - [MainNet, TestNet3, RegTest, SigNet]

### Get Status

method: `getstatus`

#### Params

- coordinator: String - name of the coordinator

#### Response

- status: String
  - [NoDetails, KnownRound, ReceivedNonce, InputsScheduled, InputsRegistered, TargetOutputRegistered, PSBTSigned]
- round: Round - Available for all statuses besides `NoDetails`

Round:

- version: Number
- roundId: String - id for the round
- amount: Number - denomination for the round
- coordinatorFee: Number - fee in satoshis for the round
- publicKey: String - coordinator's public key
- time: Number - when the round will execute, in epoch seconds
- inputType: String - enforced input script type for this round
- outputType: String - enforced output script type for this round
- changeType: String - enforced change output script type for this round
- minPeers: Number
- maxPeers: Number
- status: String - Status string that should be displayed to the user
- feeRate: Number - Estimate of the fee rate for the round

### Get Statuses

method: `getstatuses`

#### Params

None

#### Response

Json object of statuses(see above)
Example:

```json
{
  "Taproot Testnet": {
    "round": {
      "version": 0,
      "roundId": "df6577f954c426ccb601c782d455670a87d9215ea26f79ceacf9c3cf28d59385",
      "amount": 40000,
      "coordinatorFee": 1000,
      "publicKey": "e64bc6142987ea48e96293b37b719a8cb00fc4a4673c3c4e48df30bb9dbed602",
      "time": 1661502437,
      "inputType": "witness_v1_taproot",
      "outputType": "witness_v1_taproot",
      "changeType": "witness_v1_taproot",
      "minPeers": 1,
      "maxPeers": 2,
      "status": "",
      "feeRate": 1
    },
    "status": "KnownRound"
  },
  "Lightning Testnet": {
    "round": {
      "version": 0,
      "roundId": "03fea63ef0f25dcfd5e4b6aedca93b8037dce0f172415e97000a76e90d470dd7",
      "amount": 40000,
      "coordinatorFee": 1000,
      "publicKey": "e64bc6142987ea48e96293b37b719a8cb00fc4a4673c3c4e48df30bb9dbed602",
      "time": 1661502441,
      "inputType": "witness_v0_keyhash",
      "outputType": "witness_v0_scripthash",
      "changeType": "witness_v0_keyhash",
      "minPeers": 1,
      "maxPeers": 2,
      "status": "",
      "feeRate": 1
    },
    "status": "KnownRound"
  }
}

```

### List UTXOs

method: `listutxos`

#### Params

None

#### Response

list of utxos:

- address: String - bitcoin address
- amount: Number - in satoshis
- outPoint: String - the transaction outpoint in `txid:vout` format
- confirmed: Boolean - if the transaction is confirmed or not
- anonSet: Number - anonymity set of this utxo
- warning: String - optional warning about this utxo (ie address has been re-used)
- isChange: Boolean - was a change output from a coinjoin transaction
- scriptType: String - type of script this utxo is

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
- tx: String - transaction in network serialization
- numConfirmations: Number
- blockHeight: Number
- label: String

### List Channels

method: `listchannels`

#### Params

None

#### Response

list of channels:

- alias: String
- outPoint: String
- remotePubkey: String
- shortChannelId: String
- public: Boolean - if this is a public or private channel
- amount: Number - size of channel in satoshis
- active: Boolean - if the channel is currently active or not
- anonSet: Number - anonymity set of the resulting channel

### Queue Coins

method: `queuecoins`

#### Params

You can set `address` to specify an address to do the collaborative transaction to.

You can set `nodeId` to specify the node's pubkey to open the channel to.

Or you can set neither and LnVortex will generate an address to do the collaborative transaction to.

- coordinator: String - name of the coordinator
- outpoints: Array[String] - outpoints should be in the `txid:vout` format
- address: String - optional, address to do the collaborative transaction to
- nodeId: String - optional, the node's pubkey to open the channel to
- peerAddr: String - optional, IP or Onion Service's address of the peer

#### Response

null

### Cancel Coins

Cancels the queued coins

method: `cancelcoins`

#### Params

- coordinator: String - name of the coordinator

#### Response

null
