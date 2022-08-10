CREATE TABLE utxos
(
    outpoint       TEXT PRIMARY KEY NOT NULL,
    script_pub_key TEXT             NOT NULL,
    anon_set       INTEGER          NOT NULL,
    warning        TEXT,
    is_change      TEXT             NOT NULL
);
