# Project State

Updated: 2026-09-19

## Current Implementation

- Maven application modules: `shared-contracts`, `transaction-api`, and `transaction-processor`.
  `payment-e2e-tests` runs the packaged applications in separate JVMs for black-box testing.
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
  Rejections never write to the ledger and are logged with transaction/correlation
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

- 2026-09-19: Refactored `PaymentFlowIT` into five independent scenarios with shared
  bootstrap/cleanup code and fresh pinned containers plus real application JARs per
  test. Coverage retains valid intake, identical retry, conflict without mutation,
  negative/non-EUR rejection, and PostgreSQL outage/manual restart/replay with no
  premature acknowledgement. Logs are grouped by scenario. Resources are registered
  as acquired and closed in reverse order even after partial startup or test failure;
  cleanup attempts all resources and retains cleanup errors.
  The focused E2E command passed all five scenarios on 2026-09-18:
  `.\mvnw.cmd -pl payment-e2e-tests -am -Dit.test=PaymentFlowIT -Dfailsafe.failIfNoSpecifiedTests=false verify`.
  The subsequent `.\mvnw.cmd clean verify` passed on 2026-09-19 in 3m25s: 66 Surefire
  tests and 15 Failsafe tests (including five E2E), no failures, errors or skips.
  Business code and acknowledgement/recovery policies are unchanged.

- 2026-09-18: `.\mvnw.cmd clean verify` completed with Maven BUILD SUCCESS across all
  five reactor projects in 1m43s. Surefire ran 66 tests (36 API, 30 processor);
  Failsafe ran 11 (2 API, 8 processor, 1 complete end-to-end scenario), with no failures,
  errors or skips. Packaging and all integration-test/verify executions were exercised.
  The end-to-end scenario verifies real HTTP intake, one ledger row, identical retry,
  logged/acknowledged payload conflict without mutation, negative/non-EUR rejection,
  and a stopped PostgreSQL server leaving the event unacknowledged. After database
  and processor restart, the event is replayed once into the ledger. Recovery remains
  manual; there is no automatic retry or exactly-once guarantee.
  The first full run exposed a test-harness issue: Docker reassigned PostgreSQL's
  published port on restart. The test now inspects the current mapping before recovery;
  the corrected full run passed. Application logs are saved with Failsafe reports.
  GitHub Actions requires no change: its existing clean verify command and artifact
  patterns already cover Failsafe. The CI workflow running `./mvnw clean verify`
  succeeded on GitHub for commit `12dae6b`
  ([run 35352080520](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35352080520)).

- 2026-09-18: `.\mvnw.cmd -o -pl transaction-processor -am test` passed all 38 tests
  (no failures or skips) after explicit payload-conflict handling. Unit tests cover
  all four business fields, combined differences, numeric amount equality and
  conversion of a post-insert conflict to a business rejection. PostgreSQL tests
  verify exact/metadata-only duplicates, each field conflict and unchanged rows.
  Kafka tests verify amount/currency conflicts are logged with transactionId,
  correlationId and reason=PAYLOAD_CONFLICT, acknowledged, and do not stop consumption.
  Commit-failure rollback and replay still pass. API, shared contract and schema
  migrations are unchanged. Conflict history remains log-only, not a durable audit.

- 2026-09-18: `.\mvnw.cmd -o -pl transaction-processor -am test` passed all 34 tests
  (no failures or skips). The dedicated exact-duplicate publication test verifies
  two acknowledged Kafka records produce exactly one ledger row, with INSERTED then
  DUPLICATE and no consumer failure. A deferred constraint trigger now injects a
  failure at PostgreSQL COMMIT: rollback leaves no row and no advanced Kafka offset;
  removing the trigger and restarting the listener successfully replays the event.
  SQL explicitly targets the existing named unique constraint; Flyway V1 is unchanged.
  These are at-least-once/idempotent-ledger guarantees, not exactly-once processing.

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
  The CI workflow running `./mvnw clean verify` succeeded on GitHub for commit `12dae6b`.

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
- Earlier milestones deliberately skipped `clean verify`; the end-to-end milestone
  now requests the full lifecycle. See the latest validation entry for its result.

Historical focused commands (before the move to Failsafe; see README for current commands):

```powershell
.\mvnw.cmd -o '-pl=transaction-api,transaction-processor' -am '-Dtest=ReceiveTransactionTests,TransactionControllerTests,KafkaTransactionPublisherTests,TransactionReceivedContractTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o -pl transaction-processor -am '-Dtest=TransactionReceivedContractTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o '-pl=transaction-api,transaction-processor' -am '-Dtest=TransactionPublicationTests,TransactionProcessorApplicationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## Architecture Decisions

See [ADR 0001](adr/0001-transaction-intake-contract.md) and
[ADR 0002](adr/0002-ledger-consumption.md) and
[ADR 0003](adr/0003-business-rejections.md) and [ADR 0004](adr/0004-payload-conflicts.md).

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
  `ON CONFLICT ON CONSTRAINT uk_ledger_transactions_transaction_id DO NOTHING`
  and compares business fields for duplicates. The named PostgreSQL unique constraint
  is the final guarantee; unrelated database errors are not swallowed. Correlation
  and timestamps are excluded from comparison; first committed metadata is retained.
- After envelope validation, a targeted payload read classifies existing IDs before
  new-transaction rules. Equal business values return DUPLICATE; changed account,
  amount, currency or type returns a rejection with PAYLOAD_CONFLICT. The same pure
  comparison runs after an insert loses a race. No existing columns are replaced.
  Conflicts log incoming transaction/correlation IDs and reason=PAYLOAD_CONFLICT;
  normal listener return permits acknowledgement. Conflict history is not durable.
- Kafka auto-commit is disabled; RECORD acknowledgement follows committed database
  work or verified duplication for accepted events. Business rejections are temporarily
  logged and acknowledged without ledger writes. Technical/contract failures stop
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
- Add concurrent duplicate tests and process-crash testing between database and
  offset commits. The end-to-end test simulates server unavailability by stopping
  PostgreSQL, not a network partition; processor tests separately inject commit failure.
- Add authentication, status lookup,
  distributed traces, business metrics, and operational dashboards. The event field
  supplies correlation metadata; it does not itself implement distributed tracing.
- Spring Boot manages JUnit Jupiter 6.0.1, required by Spring Framework 7. The JUnit 5
  wording in `AGENTS.md` is incompatible with that stack; dependencies were not downgraded.
- Failsafe is active in `integration-test` / `verify` for `*IT` tests. All container
  tests were renamed; Surefire retains unit/MVC/contract tests. CI already invokes
  `clean verify` and collects both report directories on failure.
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
