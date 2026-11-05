-- Optional client-chosen key that makes a retried POST /orders return the first order.
-- Nullable, so existing rows and clients without the header are unaffected.
ALTER TABLE orders ADD COLUMN idempotency_key text UNIQUE;
