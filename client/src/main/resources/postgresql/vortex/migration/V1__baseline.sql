CREATE TABLE utxos
(
    outpoint       TEXT PRIMARY KEY NOT NULL,
    txid           TEXT             NOT NULL,
    script_pub_key TEXT             NOT NULL,
    anon_set       INTEGER          NOT NULL,
    warning        TEXT,
    is_change      BOOLEAN          NOT NULL,
    is_vortex      BOOLEAN          NOT NULL
);

CREATE INDEX utxos_txid_index on utxos (txid);
CREATE INDEX utxos_is_vortex_index on utxos (is_vortex);
CREATE INDEX utxos_spk_index on utxos (script_pub_key);
