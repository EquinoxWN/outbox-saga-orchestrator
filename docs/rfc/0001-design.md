# RFC 0001: outbox-saga-orchestrator design

- **Status:** Accepted (M1 implemented)
- **Author:** EquinoxWN
- **Created:** 2026

## Problem

Placing an order touches three services: the order is created, the payment is authorized and the
stock is reserved. Each service owns its data, so there is no single database transaction across
them. The classic failure is the dual write: a service commits its change and then crashes before
publishing the event, or publishes an event for a change that later rolls back. Either way the
services disagree forever. The fix has two parts: every state change and its event must commit
atomically (the transactional outbox, this milestone), and a saga must drive the steps and undo
them when one fails (later milestones).

## Goals

- Order, payment and inventory are separate Spring Boot services, each with its own PostgreSQL
  database and login role; no role can connect to another service's database.
- Every state change writes its event to an `outbox` table in the same local transaction, so the
  event exists if and only if the change committed.
- The outbox writer refuses to run outside a transaction, so a caller cannot forget it.
- Each service is safe under retries and concurrency: payments and reservations are idempotent
  per order, and stock can never be oversold.
- Later: Debezium's outbox event router relays outbox rows to Kafka (M2), an orchestrator runs the
  saga with compensations (M2), de-duplicating consumers, fault injection with Toxiproxy and a
  saga timeline (M3).

## Non-goals

- Distributed transactions (two-phase commit / XA): they couple the services' availability.
- A production deployment; services run locally and in tests.
- Running as a hosted production service.

## Proposed design

![architecture](../architecture.png)

```
POST /orders ──► order-service ──► BEGIN; INSERT orders; INSERT outbox (OrderCreated); COMMIT   ─► order_db
POST /payments ─► payment-service ─► BEGIN; INSERT payments ON CONFLICT DO NOTHING; INSERT outbox; COMMIT ─► payment_db
POST /reservations ─► inventory-service ─► BEGIN; claim order id; UPDATE stock WHERE available >= n; INSERT outbox; COMMIT ─► inventory_db
                                                                     (M2: Debezium outbox router ─► Kafka ─► saga orchestrator)
```

| Part | M1 implementation |
|---|---|
| Services | Spring Boot 4.1 (Java 21), one Maven module each, plain `JdbcClient`, Flyway migrations |
| Databases | One PostgreSQL database and login role per service (`db/init.sql`); `CONNECT` revoked from `PUBLIC` |
| Outbox | Shared `outbox` module: `OutboxWriter.append` with `Propagation.MANDATORY`; table uses Debezium's default columns (`id`, `aggregatetype`, `aggregateid`, `type`, `payload` jsonb) |
| Payments | Authorize up to a configured limit, decline above it; `ON CONFLICT (order_id) DO NOTHING` makes retries return the first decision without a second event |
| Inventory | Claim the order id first, then `UPDATE stock SET available = available - n WHERE available >= n`; the row lock and re-checked condition prevent overselling |
| Input | Bean Validation on every request body; SKUs restricted to `[A-Z0-9-]` |
| Tests | A real PostgreSQL 18 from embedded binaries (zonky), no Docker |

## Alternatives considered

| Option | Why not (yet) |
|---|---|
| Publish to Kafka directly after commit | The dual-write problem itself: a crash between commit and publish loses the event. |
| Two-phase commit across the three databases | Every service blocks when any one is down, and most brokers and cloud databases do not support XA well. |
| Event sourcing | Solves atomicity too, but changes how every service stores data; the outbox keeps normal tables. |
| One shared database with three schemas | Easier joins, but nothing stops one service from reading or changing another's tables; separate databases and roles enforce ownership. |
| Testcontainers for PostgreSQL | The standard choice, but it needs Docker; embedded binaries give the same real PostgreSQL in CI and on a laptop without it (Testcontainers joins in M2 for Kafka and Debezium). |

## Measurement plan

- M1: per service, a real PostgreSQL proves atomicity (rollbacks leave neither change nor event),
  idempotency under concurrent duplicates, no overselling under 40 concurrent reservations, input
  validation, and that each role is refused by the other services' databases.
- M2: end-to-end saga runs through Kafka, including compensation when payment is declined.
- M3: a fault-injection matrix (Toxiproxy) showing every failure mode ends in a consistent state.

## Milestones

- **M1 (done):** three services with their own databases and roles, transactional outbox, idempotent
  payments and reservations, 22 tests on real PostgreSQL.
- **M2:** Debezium outbox router to Kafka, orchestrator state machine with compensations
  (release stock, cancel order), consumer de-duplication by event id.
- **M3:** Toxiproxy fault injection, stuck-saga alerts, saga timeline UI, proof table.

## Risks and open questions

- The outbox table grows forever until the relay deletes or partitions it; M2 adds cleanup after
  Debezium has captured rows.
- Events are delivered at least once by design, so every consumer must de-duplicate by event id
  (M2); M1 already makes the receiving operations idempotent per order.
- Embedded PostgreSQL binaries must match supported platforms; CI runs them on Linux, the author
  verified Windows.
