# ADR 0002: The outbox writer joins the caller's transaction and refuses to run without one

- **Status:** Accepted

## Context

The transactional outbox only works if the event row and the state change commit in the same
database transaction. A helper that opens its own transaction, or that a developer calls after the
service method has already committed, silently reintroduces the dual-write bug the pattern exists
to prevent, and nothing fails until events go missing in production.

## Decision

`OutboxWriter.append` is annotated `@Transactional(propagation = MANDATORY)`. It always joins the
transaction that is already open and throws `IllegalTransactionStateException` if there is none.
Services call it from inside their own `@Transactional` methods, after writing the state change.
The outbox table lives in each service's own database and uses Debezium's default outbox column
names, so M2 can relay it without a schema change.

## Consequences

- Forgetting the transaction is a loud error at the first test run, not a silent data bug; a test
  proves it.
- If the event cannot be written (for example the payload fails to serialise), the state change
  rolls back too; another test proves that, and the reverse case.
- Every service carries its own outbox table and migration, at the cost of one extra table per
  database.
- Events are not published yet: until the M2 relay, they accumulate in the outbox, which is exactly
  the state the relay is designed to start from.
