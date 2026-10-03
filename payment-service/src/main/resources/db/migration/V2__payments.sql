CREATE TABLE payments (
    order_id     uuid        PRIMARY KEY,
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),
    status       text        NOT NULL CHECK (status IN ('AUTHORIZED', 'DECLINED')),
    created_at   timestamptz NOT NULL DEFAULT now()
);
