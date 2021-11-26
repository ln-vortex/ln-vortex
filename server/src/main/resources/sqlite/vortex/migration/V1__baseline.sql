CREATE TABLE `banned_utxos`
(
    `outpoint`     VARCHAR(254) PRIMARY KEY NOT NULL,
    `banned_until` TIMESTAMP                NOT NULL,
    `reason`       VARCHAR(254)             NOT NULL
);

CREATE TABLE `rounds`
(
    `round_id`    VARCHAR(254) PRIMARY KEY NOT NULL,
    `status`      VARCHAR(254)             NOT NULL,
    `round_time`  TIMESTAMP                NOT NULL,
    `fee_rate`    VARCHAR(254)             NOT NULL,
    `mix_fee`     INTEGER                  NOT NULL,
    `input_fee`   INTEGER                  NOT NULL,
    `output_fee`  INTEGER                  NOT NULL,
    `amount`      INTEGER                  NOT NULL,
    `psbt`        VARCHAR(254),
    `transaction` VARCHAR(254),
    `txid`        VARCHAR(254),
    `profit`      INTEGER
);

CREATE TABLE `alices`
(
    `peer_id`             VARCHAR(254) PRIMARY KEY NOT NULL,
    `round_id`            VARCHAR(254)             NOT NULL,
    `purpose`             INTEGER                  NOT NULL,
    `coin`                INTEGER                  NOT NULL,
    `account`             INTEGER                  NOT NULL,
    `chain`               INTEGER                  NOT NULL,
    `nonce_index`         INTEGER                  NOT NULL,
    `nonce`               VARCHAR(254) UNIQUE      NOT NULL,
    `remix_confirmations` INTEGER,
    `num_inputs`          INTEGER                  NOT NULL,
    `blinded_output`      VARCHAR(254),
    `change_spk`          VARCHAR(254),
    `blind_sig`           VARCHAR(254),
    `signed`              INTEGER                  NOT NULL,
    constraint `fk_roundId` foreign key (`round_id`) references `rounds` (`round_id`) on update NO ACTION on delete NO ACTION
);

CREATE TABLE `registered_inputs`
(
    `outpoint`    VARCHAR(254) PRIMARY KEY NOT NULL,
    `output`      VARCHAR(254)             NOT NULL,
    `input_proof` VARCHAR(254)             NOT NULL,
    `index`       INTEGER,
    `round_id`    VARCHAR(254)             NOT NULL,
    `peer_id`     VARCHAR(254)             NOT NULL,
    constraint `fk_roundId` foreign key (`round_id`) references `rounds` (`round_id`) on update NO ACTION on delete NO ACTION,
    constraint `fk_peerId` foreign key (`peer_id`) references `alices` (`peer_id`) on update NO ACTION on delete NO ACTION
);

CREATE TABLE `registered_outputs`
(
    `output`   VARCHAR(254) PRIMARY KEY NOT NULL,
    `sig`      VARCHAR(254)             NOT NULL,
    `round_id` VARCHAR(254)             NOT NULL,
    constraint `fk_roundId` foreign key (`round_id`) references `rounds` (`round_id`) on update NO ACTION on delete NO ACTION
);
