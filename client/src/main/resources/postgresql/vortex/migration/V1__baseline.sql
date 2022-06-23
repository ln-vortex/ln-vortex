CREATE TABLE utxos
(
    outpoint  TEXT PRIMARY KEY NOT NULL,
    anon_set  INTEGER          NOT NULL,
    is_change TEXT             NOT NULL
);
