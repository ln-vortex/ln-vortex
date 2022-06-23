CREATE TABLE utxos
(
    outpoint  VARCHAR(254) PRIMARY KEY NOT NULL,
    anon_set  INTEGER                  NOT NULL,
    is_change VARCHAR(254)             NOT NULL
);
