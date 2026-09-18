# Project State

Updated: 2026-09-18

## Current Implementation

- Maven modules: `shared-contracts`, `transaction-api`, and `transaction-processor`.
- Parent manages Java 25 and Spring Boot 4.0.1; Maven Wrapper pins Maven 3.9.0.
- `shared-contracts` contains framework-free `contracts.v1.TransactionReceived`
  (including `correlationId`) and `TransactionType` (`TRANSFER`).
- `transaction-api` depends on the contracts module and implements
  `POST /api/v1/transactions`, structural validation, ID/header matching, event mapping,
  Kafka publication, and sanitized Problem Details errors.
- Intake requires a client-supplied `correlationId` in the JSON body (1–64 ASCII
  letters, digits, underscores, or hyphens). The event and 202 response preserve it.
  It is independent of `transactionId` and does not change partitioning or idempotency.
- A plain application service uses a publisher port and injected `Clock`. The Kafka
  adapter waits for confirmation before the controller returns 202; uncertain or
  failed publication returns 503. Kafka producer idempotence and `acks=all` are enabled.
- `transaction-processor` depends on `shared-contracts` and Boot's Jackson starter.
  A contract test verifies that its Kafka JSON deserializer preserves correlation
  metadata. Flyway V1 creates `ledger_transactions` in the default application schema.
  The consumer now maps shared events through a plain application service to a
  transactional JDBC ledger store. Pure `TransactionRules` require positive amounts,
  exactly EUR, and TRANSFER before persistence. `ProcessingResult` distinguishes
  acceptance from business rejection; all rejection reasons are accumulated.
  Rejections never call the ledger store and are logged with transaction/correlation
  IDs before normal listener return permits acknowledgement. They are not durable.
  Explicit JSON deserialization ignores Java type headers. Consumer group and topic
  are configurable, with local defaults.
- Flyway's Boot starter, PostgreSQL database support, and PostgreSQL JDBC runtime
  driver were already declared; no duplicate dependencies or POM changes were needed.
- Ledger columns use a generated BIGINT primary key, a required unique transaction ID,
  required correlation/account IDs, NUMERIC(17,2), currency/type strings, and required
  TIMESTAMPTZ received/processed instants. Processing time is supplied by the writer.
  The only additional index is `(account_id, received_at DESC)` for account history.
- Container images are pinned: `apache/kafka-native:4.1.1` and `postgres:17.6`.
- Root `.gitignore`, README, architecture document, and the first ADR exist.
- Local Compose infrastructure provides Kafka 4.1.1 (single-node KRaft) and PostgreSQL
  17.6 on loopback ports 9092/5432, with named volumes, healthchecks, and a dedicated
  network. `.env.example` contains development defaults. Host Spring applications
  use environment-configurable connections; only the processor connects to the database.
- GitHub Actions CI runs `./mvnw clean verify` on pushes and pull requests targeting
  `main` and `codex/build-mvp`, using Temurin 25, Maven caching, and read-only contents
  permissions. Surefire/Failsafe reports are uploaded only on failure.

## Validated State

- 2026-09-18: `.\mvnw.cmd -o -pl transaction-processor -am test` passed all 33 tests
  after business-rule separation (no failures or skips). Unit coverage includes all
  eight rule combinations, positive boundaries, zero/negative/missing amounts, exact
  EUR matching, missing type, and no store calls on rejection. Kafka + PostgreSQL
  integration proves a combined rejection logs IDs/reasons, creates no ledger row,
  advances its offset, and permits subsequent processing. Technical-failure replay
  and deduplication still pass. No API, shared-contract, or migration changes.

- 2026-09-18: `.\mvnw.cmd -o -pl transaction-processor -am test` passed all 10 processor
  tests. New application unit tests cover mapping, validation and storage failures.
  Kafka + PostgreSQL integration verifies persisted fields and correlation logging,
  spoofed Java type headers, identical duplicates, SQL failure without offset
  advancement, replay after listener restart, and conflicting duplicate failure.
  API, shared contracts, and Flyway V1 are unchanged.

- 2026-09-18: `.\mvnw.cmd -o -pl transaction-processor -am test` passed all 6 processor
  tests, including 4 new PostgreSQL 17.6/Testcontainers migration tests. They prove
  V1 application, no reapplication, the default-schema table and named unique
  constraint, generated IDs, exact NUMERIC(17,2) and instant storage, duplicate-ID
  rejection (23505), and null-ID rejection (23502). No API files were changed.

- Compose configuration validated with `docker compose --env-file .env.example config --quiet`.
  Both services became healthy in an isolated validation project. A Kafka message and
  PostgreSQL row survived `down` / `up` and were read by Java clients on the host via
  localhost:9092 and localhost:5432. Temporary validation resources were cleaned up.
- Both Spring context tests passed after adding environment-based connection properties.

- CI workflow syntax validated locally with actionlint 1.7.7 (no diagnostics).
  The workflow has not yet run on GitHub; no commit, push, or publication was performed.

JDK 25.0.1 and Docker Desktop are available. Docker tests require access outside this
session's sandbox. Focused Maven commands used offline dependency resolution from
the existing cache; Docker pulled the pinned images as needed.

- 36 API unit/MVC tests: 3 mapping/service tests, 5 Kafka adapter tests, and 28 HTTP tests.
  Coverage includes validation, identity mismatch, unchanged business identity on
  retries, the absence of cross-request payload conflict detection, publication
  waiting/failure/timeout/interruption, and sanitized error bodies.
- Correlation coverage includes required-field validation, invalid and maximum-length
  IDs, exact response/event propagation, retries, and isolation between requests.
- One processor contract test passes using the actual Kafka JSON deserializer.
  Its initial compilation exposed missing Jackson classes in the processor;
  adding `spring-boot-starter-jackson` resolved that dependency gap.
- Kafka publication integration test passed: HTTP intake to real Kafka, versioned
  JSON fields, decimal amount, timestamp round-trip, record key, absence of Java type
  headers, and repeat submissions retaining the same transaction ID, correlation ID,
  and partition.
- The integration test exposed a producer-listener generic type mismatch with Boot
  auto-configuration; this was fixed and the test passed on rerun.
- The processor context test now passes with Flyway V1 applied automatically on startup.
- `.\mvnw.cmd clean verify` was deliberately skipped at the user's request. Packaging
  and the full clean lifecycle have not been revalidated in this change.

Commands used (from the root, PowerShell):

```powershell
.\mvnw.cmd -o '-pl=transaction-api,transaction-processor' -am '-Dtest=ReceiveTransactionTests,TransactionControllerTests,KafkaTransactionPublisherTests,TransactionReceivedContractTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o -pl transaction-processor -am '-Dtest=TransactionReceivedContractTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o '-pl=transaction-api,transaction-processor' -am '-Dtest=TransactionPublicationTests,TransactionProcessorApplicationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## Architecture Decisions

See [ADR 0001](adr/0001-transaction-intake-contract.md) and
[ADR 0002](adr/0002-ledger-consumption.md) and
[ADR 0003](adr/0003-business-rejections.md).

- `Idempotency-Key` must equal the client-supplied `transactionId`; Kafka uses that
  ID as its record key. This does not guarantee ordering across an account.
- Preserve `correlationId` across service boundaries, retries, and derived events.
  It is workflow metadata and must be excluded from business-payload conflict checks.
  The unreleased version-1 contract is extended in place; existing callers must add
  the required field. Previously published events do not acquire correlation IDs.
- Repeated requests can publish duplicate events. The API does not store requests
  or reject changed payloads under an existing ID. There is no exactly-once claim.
- PostgreSQL now enforces a unique `transaction_id` through
  `uk_ledger_transactions_transaction_id`. The transactional JDBC adapter uses
  `ON CONFLICT DO NOTHING` and compares business fields for duplicates. Correlation
  and timestamps are excluded from comparison; first committed metadata is retained.
- Kafka auto-commit is disabled; RECORD acknowledgement follows committed database
  work or verified duplication for accepted events. Business rejections are temporarily
  logged and acknowledged without ledger access. Technical/contract failures stop
  the listener without skipping records.
  Recovery requires correcting the failure and restarting; no retry/DLQ topics exist.
  This is at-least-once delivery with idempotent database effects.
- Amount sign and supported-currency checks belong to the processor. An intake 202
  confirms publication, not business validation or accounting completion.
- Normal startup requires an externally provisioned topic. The test launcher and
  API integration configuration create a disposable three-partition topic; processor
  integration configuration uses one partition. Both use one replica.

## Current Gaps and Known Build Notes

- Define durable business rejection storage and operational recovery for malformed
  events; currently technical/contract failures stop consumption. Add listener
  health monitoring: the application process can remain alive after the listener stops.
- Add concurrent duplicate tests, process-crash testing,
  and a combined HTTP-to-ledger test. SQL failure/restart is tested, not a database
  network outage or a crash between database and offset commits.
- Add authentication, status lookup,
  distributed traces, business metrics, and operational dashboards. The event field
  supplies correlation metadata; it does not itself implement distributed tracing.
- Spring Boot manages JUnit Jupiter 6.0.1, required by Spring Framework 7. The JUnit 5
  wording in `AGENTS.md` is incompatible with that stack; dependencies were not downgraded.
- Failsafe remains in `pluginManagement` only. Current container-backed `*Tests` run
  through Surefire; future `*IT` tests need explicit Failsafe lifecycle activation.
- Maven/Jansi/Guava and Mockito emit Java 25 native-access, deprecated-Unsafe, and
  dynamic-agent warnings. These did not fail the focused tests.

## Next Objective

Persist business rejection outcomes idempotently, replacing the temporary log-and-ack
policy with durable traceability before acknowledgement.

## Acceptance Criteria for the Next Objective

- Define rejection storage and repeated/conflicting rejection policy in an ADR.
- Persist outcomes idempotently and preserve correlation metadata without changing
  the API's database isolation or acknowledging before durable completion.
- Test valid and rejected HTTP-to-ledger flows, repeated delivery, and recovery.
- Expose stopped-consumer health and document the operator recovery procedure.
