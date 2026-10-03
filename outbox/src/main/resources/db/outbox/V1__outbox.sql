-- Transactional outbox, with the column names Debezium's outbox event router expects.
CREATE TABLE outbox (
    id            uuid        PRIMARY KEY,
    aggregatetype text        NOT NULL,
    aggregateid   text        NOT NULL,
    type          text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_created_at ON outbox (created_at);
