# Realtime Payment Platform — Project Instructions

## Purpose

Build a portfolio-grade, real-time payment processing platform that demonstrates reliable distributed-system design with Java, Spring Boot, Apache Kafka, PostgreSQL, observability, and production-oriented engineering practices.

## Technology Stack

- Java 25
- Spring Boot 4.0.1
- Maven multi-module build
- Apache Kafka
- PostgreSQL and Flyway
- JUnit 5 and Testcontainers
- Spring Boot Actuator
- Docker Compose for local development
- GitHub Actions for CI

## Modules

- `shared-contracts`: versioned event contracts and shared value types only.
- `transaction-api`: REST API, request validation, idempotency-key handling, and Kafka event publication.
- `transaction-processor`: Kafka event consumption, deterministic business validation, and idempotent ledger persistence.

## Architecture Rules

- Use pragmatic hexagonal architecture. Create ports only at meaningful boundaries such as Kafka, PostgreSQL, time, and ID generation.
- `transaction-api` must not access the ledger database directly.
- `shared-contracts` must not contain Spring components, JPA entities, configuration, or business logic.
- Use a stable `transactionId` and a unique database constraint to ensure business idempotency.
- Kafka acknowledgements must happen only after the expected processing operation succeeds.
- Keep sensitive data out of logs and configuration files.

## Scope Control

Do not add Kubernetes, Angular, Kafka Streams, a risk engine, or an AI assistant before the MVP flow is reliable, tested, and documented:

`Transaction API -> Kafka -> Transaction Processor -> PostgreSQL`

## Validation

Run from the repository root on Windows:

```powershell
.\mvnw.cmd clean verify
```

Every change must include appropriate tests. Integration tests must use pinned Testcontainers image versions; do not use `latest`.

## Workflow

Before implementing a change:

1. Read this file and `docs/PROJECT_STATE.md`.
2. Check the relevant ADRs in `docs/adr/` when they exist.
3. State the intended outcome, constraints, and validation criteria.

After meaningful work, update `docs/PROJECT_STATE.md` with the validated state, decisions, and next objective.
