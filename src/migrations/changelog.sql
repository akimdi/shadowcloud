--liquibase formatted sql

--changeset shadowcloud:1
CREATE TABLE IF NOT EXISTS sc_keys (
  key_id         CHAR(36) PRIMARY KEY NOT NULL,
  for_encryption BOOLEAN              NOT NULL,
  for_decryption BOOLEAN              NOT NULL,
  serialized_key VARBINARY            NOT NULL
);

--changeset shadowcloud:2
CREATE TABLE IF NOT EXISTS sc_akka_journal (
  persistence_id VARCHAR(255)          NOT NULL,
  sequence_nr    BIGINT                NOT NULL,
  ordering       BIGINT AUTO_INCREMENT NOT NULL,
  tags           ARRAY                 NOT NULL,
  message        VARBINARY             NOT NULL,
  PRIMARY KEY (persistence_id, sequence_nr)
);

--changeset shadowcloud:3
CREATE TABLE IF NOT EXISTS sc_akka_snapshots (
  persistence_id VARCHAR   NOT NULL,
  sequence_nr    BIGINT    NOT NULL,
  timestamp      BIGINT    NOT NULL,
  snapshot       VARBINARY NOT NULL,
  PRIMARY KEY (persistence_id, sequence_nr)
);

CREATE INDEX IF NOT EXISTS snapshot_index
  ON sc_akka_snapshots (persistence_id, sequence_nr DESC, timestamp DESC);

--changeset shadowcloud:4
CREATE TABLE IF NOT EXISTS sc_sessions (
  storage_id VARCHAR NOT NULL,
  key VARCHAR NOT NULL,
  data VARBINARY NOT NULL,
  PRIMARY KEY (storage_id, key)
);

--changeset shadowcloud:5
ALTER TABLE sc_keys
    ADD IF NOT EXISTS region_set ARRAY NOT NULL DEFAULT ();