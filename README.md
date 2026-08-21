# Funds Transfer Service

A Spring Boot backend service that models a fintech-style **funds transfer and account
ledger system**, built to explore two problems that matter once money is involved:
**idempotency** (never double-process the same transfer) and **failure recovery**
(transient errors get retried safely; real failures get surfaced, not silently retried
forever).

---

## System Highlights

- REST endpoint takes a client-supplied `Idempotency-Key`; retried requests with the same
  key return the original transfer instead of creating a duplicate, backed by a unique
  DB constraint (not just an in-memory check) so concurrent duplicate requests can't race
  past it.
- Transfer creation and account debit/credit are decoupled through **Kafka**: the API call
  persists the transfer and publishes a `transfer-initiated` event; a separate consumer
  performs the actual balance movement.
- The consumer is idempotent on transfer status, so a redelivered/duplicate Kafka message
  is a safe no-op instead of a double debit.
- Transient failures (DB blips, optimistic-lock conflicts from a concurrent transfer on the
  same account) are retried with exponential backoff via Spring Kafka's non-blocking retry
  topics; after repeated failure the event lands on a dead-letter topic and the transfer is
  marked `FAILED` with a reason instead of retrying forever.
- Business failures (insufficient funds) are not retried at all - they're deterministic, so
  the transfer is marked `FAILED` immediately.
- Optimistic locking (`@Version`) on `Account` protects concurrent balance updates.

---

## Tech Stack

Java 17 - Spring Boot 3 - Spring Data JPA - Spring Kafka - PostgreSQL - Maven

---

## Architecture

```
Client --POST /v1/transfers (Idempotency-Key)--> Controller --> TransferService
                                                                     |
                                                          save Transfer (INITIATED)
                                                                     |
                                                          publish transfer-initiated
                                                                     v
                                                            Kafka topic (3 partitions,
                                                            keyed by transferId)
                                                                     |
                                                     TransferEventConsumer (@KafkaListener)
                                                                     |
                                                     debit(from) / credit(to), status update
                                                                     |
                                        transient failure -> retry topic (backoff) -> DLT -> FAILED
```

- `Transfer.status` moves `INITIATED -> PROCESSING -> SUCCESS|FAILED`.
- `GET /v1/transfers/{id}` lets a client poll for the outcome of an async transfer.

---

## Running locally

### 1. Start Postgres and Kafka

```bash
docker compose up -d
```

### 2. Run the application

```bash
mvn spring-boot:run
```

### 3. Create a transfer

```bash
curl -X POST http://localhost:8080/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 3f9a2b7e-1c4d-4e2a-9f10-7a1b2c3d4e5f" \
  -d '{"fromAccountId": 1, "toAccountId": 2, "amount": 100.00}'
```

Repeating the same request with the same `Idempotency-Key` returns the original transfer.

### 4. Check status

```bash
curl http://localhost:8080/v1/transfers/{id}
```

---

## Tests

```bash
mvn test
```

Unit tests cover the idempotency fast path and the unique-constraint race fallback, the
idempotent no-op on a duplicate/redelivered event, the insufficient-funds business failure,
and the optimistic-lock conflict that's expected to propagate so Kafka retries it.

These are all mocked-repository unit tests, which is enough to verify the Java-level control
flow but not real Postgres transaction/isolation behavior - see "Known limitations" below for
the two places that distinction actually matters.

---

## Future improvements

- Distributed tracing (OpenTelemetry) across the produce/consume boundary
- Outbox pattern instead of publish-after-commit, to remove the small window where the
  transfer is saved but the Kafka publish could fail
- Reconciliation job to reconcile `PROCESSING` transfers stuck past a timeout

---

## Known limitations

- **`PROCESSING`'s visibility is flushed, not committed.** `processTransfer` explicitly
  flushes the `PROCESSING` write so the UPDATE is actually issued to Postgres instead of
  sitting in Hibernate's persistence context until commit - but a flush is not a commit.
  Under Postgres's default READ COMMITTED isolation, another connection (e.g. a client
  polling `GET /v1/transfers/{id}`) still can't see `PROCESSING` until the whole
  `processTransfer` transaction commits, which only happens once, at the very end of the
  method, alongside the final `SUCCESS`/`FAILED` write. So the flush fixes "the UPDATE is
  never even sent to the database mid-method" - a real bug - but does not, on its own,
  make `PROCESSING` visible to a concurrent poller. Doing that would mean committing the
  `PROCESSING` write in its own transaction before the debit/credit step begins, which is
  a bigger structural change than this fix makes.
- **The unique-constraint race fallback and the `PROCESSING` flush are unverified against
  real Postgres.** `TransferServiceTest` mocks `AccountRepository`/`TransferRepository`,
  so it can assert on Java-level control flow (which method got called, what was
  returned) but has no concept of a real connection, a real transaction, or Postgres's
  "current transaction is aborted" behavior. Proving either fix actually holds against a
  real database - especially the unique-constraint race, which needs two genuinely
  concurrent transactions - would need a Testcontainers-backed integration test. That's a
  real addition (new test dependencies, real concurrency coordination), not a small one,
  so it's not included here.

## Author

Varsha Sellen
Backend Engineer - Java | Spring Boot | Kafka | Distributed Systems
Based in Italy | Open to EU backend roles
