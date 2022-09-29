---
sidebar_position: 2
---

# Protocol Documentation

This is an adaptation of [ZeroLink](https://github.com/nopara73/ZeroLink/blob/master/README.md) made to be usable for
opening lightning channels.

## Considerations

We could not directly use the ZeroLink framework for opening lightning channels because when opening a lightning channel
we have only 10 minutes to broadcast the funding transaction from when we negotiate the channel with our channel peer.
Unless we have coinjoin rounds every 10 minutes this is not possible under the initial ZeroLink spec because the blinded
output must be given during the input registration phase, and we can't know the output until we negotiate with the
channel peer.

## Protocol

See [detailed-protocol.jpeg](detailed-protocol.jpeg) for a visualization of the protocol.

#### 1. Pending & Queueing Phase

Many Alices queue for the next CoinJoin by sending an `AskNonce` message, prompting the coordinator to generate a unique
nonce for Alice that will later be used in the blind signature.

#### 2. Input Registration

The coordinator sends an `AskInputs` message to every Alice that asked for a nonce which then prompts the beginning of
the Input Registration phase.

Many Alices begin channel negotiation with their peer, then register to the coordinator:

- confirmed utxos as the inputs of the CoinJoin
- proofs - a signed witness script of a transaction that commits to their unique nonce
- their change address
- the blinded output

Coordinator checks if inputs have enough coins, are unspent, confirmed, were not registered twice and that the provided
proofs are valid, and change address is of the correct type, then signs the blinded output. Alices unblind their signed
and blinded outputs.

#### 3. Output Registration

Alice under a new tor identity as Bob sends the unblinded signature with the output to the coordinator.

Coordinator verifies the signature is valid and the output address is of the correct type.

#### 4. Signing Phase

Coordinator builds the unsigned CoinJoin transaction and gives it to Alices for signing. Alices sign and notify their
channel peer of the funding transaction for their channel. When all the Alices signatures arrive, the coordinator
combines the signatures and propagates the CoinJoin on the network, and sends it to the Alices.

## Privacy Considerations

### Dealing with change

The goal of this CoinJoin is to open a lightning channel without having to reveal which utxos are yours. Because of
this, it is important to preserve privacy after the round, specifically with the change output(s).

Since the users are not mixing to themselves and instead directly into a lightning channel, we must consider the
implications of how easily inputs and change can be linked. For example, if two users are mixing to open a 1 BTC channel
each, and user A registers with a 10 BTC input and user B registers with a 2 BTC output, it will be very obvious whose
change output is whose. Later if this change output is spent in another transaction related to their lightning node they
could easily reveal which inputs were theirs in the CoinJoin transaction. This will be known as the change problem.

#### Mixing change outputs in the CoinJoin

A potential solution to the change problem is mixing the change outputs in the coinjoin transaction. This can be done by
splitting the change into many outputs of the same denomination and then having a single extra change is the leftover
from the all the equal amount outputs. Doing this will make it so the user receives many change outputs back and these
will be of the size where the user will not need a change output for the following round. This however will require
substantial changes to the protocol. Alice will instead need to register many blinded outputs all under different Bob
identities.

#### Allowing only mixed inputs

Another potential solution to the change problem is having a separate CoinJoin round that mixes coins back to the user
themselves, then enforcing that the inputs for the lightning round must come from a mix. This way there would be no
change outputs in the opening of the lightning channel and thus harder to link to a single lightning node. The tradeoff
here is that it is more expensive for the user and on-chain, it requires multiple transactions and the user will need to
pay coordinator fees multiple times likely. This does come with an added benefit of allowing the users to be able to mix
their coins many times before opening the channel to give themselves potentially better privacy guarantees.
