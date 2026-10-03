CREATE TABLE orders (
    id           uuid        PRIMARY KEY,
    customer_id  text        NOT NULL,
    sku          text        NOT NULL,
    quantity     integer     NOT NULL CHECK (quantity > 0),
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),
    status       text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
