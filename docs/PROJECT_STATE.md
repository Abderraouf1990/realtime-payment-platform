# Project State

## Current Validated State

- Maven multi-module repository initialized.
- Modules: `shared-contracts`, `transaction-api`, and `transaction-processor`.
- Maven Wrapper added for reproducible builds.
- Parent POM manages Java 25 and Spring Boot 4.0.1.
- `shared-contracts` inherits from the parent POM.
- `transaction-api` has Spring Web MVC, validation, Kafka, Actuator, and Testcontainers dependencies.
- `transaction-processor` has Kafka, JPA, PostgreSQL, Flyway, Actuator, and Testcontainers dependencies.
- Both Spring Boot applications currently contain only bootstrap classes and context-load tests.

## Architecture Decisions

- Apache Kafka is the event backbone.
- PostgreSQL is the ledger persistence store.
- A stable `transactionId` is the business idempotency key.
- A database unique constraint will enforce one ledger entry per transaction.
- `transaction-api` publishes `TransactionReceived`.
- `transaction-processor` consumes the event and persists the result.
- Tests use Testcontainers; image versions must be pinned.

## Current Gaps

- Verify `.\mvnw.cmd clean verify` with JDK 25 configured in the environment.
- Add `.gitignore`.
- Add a public `README.md`.
- Add Docker Compose for Kafka or Redpanda and PostgreSQL.
- Pin Testcontainers image versions.
- Add GitHub Actions CI.
- Create `docs/architecture/` and `docs/adr/`.

## Next Objective

Implement the first event contract in `shared-contracts`:

```java
TransactionReceived
```

Then implement `POST /api/v1/transactions` in `transaction-api`:

1. Validate the request structure.
2. Require an idempotency key.
3. Map the request to `TransactionReceived`.
4. Publish it to the `transactions.received` Kafka topic.
5. Return `202 Accepted` with a tracking identifier.

## Definition of Done for the Next Objective

- The API accepts a valid request and publishes exactly one event.
- Invalid requests return a documented 4xx response.
- Unit tests cover request validation and event mapping.
- An integration test proves Kafka publication.
- The Maven build passes with `.\mvnw.cmd clean verify`.
