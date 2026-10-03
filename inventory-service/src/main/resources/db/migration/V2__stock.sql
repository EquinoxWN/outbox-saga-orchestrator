CREATE TABLE stock (
    sku       text    PRIMARY KEY,
    available integer NOT NULL CHECK (available >= 0)
);
CREATE TABLE reservations (
    order_id   uuid        PRIMARY KEY,
    sku        text        NOT NULL,
    quantity   integer     NOT NULL CHECK (quantity > 0),
    status     text        NOT NULL CHECK (status IN ('PENDING', 'RESERVED', 'REJECTED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
-- Demo catalogue.
INSERT INTO stock (sku, available) VALUES ('SKU-1', 100), ('SKU-2', 5), ('SKU-LAST', 1);
