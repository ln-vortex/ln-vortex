CREATE TABLE `banned_utxos`
(
    `outpoint`     TEXT PRIMARY KEY NOT NULL,
    `banned_until` TIMESTAMP        NOT NULL,
    `reason`       TEXT             NOT NULL
);

CREATE TABLE `rounds`
(
    `round_id`    TEXT PRIMARY KEY NOT NULL,
    `status`      TEXT             NOT NULL,
    `round_time`  TIMESTAMP        NOT NULL,
    `fee_rate`    TEXT             NOT NULL,
    `mix_fee`     INTEGER          NOT NULL,
    `input_fee`   INTEGER          NOT NULL,
    `output_fee`  INTEGER          NOT NULL,
    `amount`      INTEGER          NOT NULL,
    `psbt`        TEXT,
    `transaction` TEXT,
    `txid`        TEXT,
    `profit`      INTEGER
);

CREATE TABLE `alices`
(
    `peer_id`             TEXT PRIMARY KEY NOT NULL,
    `round_id`            TEXT             NOT NULL,
    `purpose`             INTEGER          NOT NULL,
    `coin`                INTEGER          NOT NULL,
    `account`             INTEGER          NOT NULL,
    `chain`               INTEGER          NOT NULL,
    `nonce_index`         INTEGER          NOT NULL,
    `nonce`               TEXT UNIQUE      NOT NULL,
    `remix_confirmations` INTEGER,
    `num_inputs`          INTEGER          NOT NULL,
    `blinded_output`      TEXT,
    `change_spk`          TEXT,
    `blind_sig`           TEXT,
    `signed`              BOOLEAN          NOT NULL,
    constraint `fk_roundId` foreign key (`round_id`) references `rounds` (`round_id`) on update NO ACTION on delete NO ACTION
);

CREATE TABLE `registered_inputs`
(
    `outpoint`    TEXT PRIMARY KEY NOT NULL,
    `output`      TEXT             NOT NULL,
    `input_proof` TEXT             NOT NULL,
    `index`       INTEGER,
    `round_id`    TEXT             NOT NULL,
    `peer_id`     TEXT             NOT NULL,
    constraint `fk_roundId` foreign key (`round_id`) references `rounds` (`round_id`) on update NO ACTION on delete NO ACTION,
    constraint `fk_peerId` foreign key (`peer_id`) references `alices` (`peer_id`) on update NO ACTION on delete NO ACTION
);

CREATE TABLE `registered_outputs`
(
    `output`   TEXT PRIMARY KEY NOT NULL,
    `sig`      TEXT             NOT NULL,
    `round_id` TEXT             NOT NULL,
    constraint `fk_roundId` foreign key (`round_id`) references `rounds` (`round_id`) on update NO ACTION on delete NO ACTION
);
