# Realtime Payment Platform

A Java 25 / Spring Boot 4.0.1 payment-processing demonstrator with the intended flow:

`Transaction API -> Kafka -> Transaction Processor -> PostgreSQL`

The API publishes events and the processor consumes them into the PostgreSQL ledger,
with database-backed deduplication and commit-before-acknowledgement ordering for
accepted transactions and durable business rejections.

## Modules

| Module | Responsibility |
| --- | --- |
| `shared-contracts` | Framework-free versioned events and shared value types |
| `transaction-api` | HTTP structural validation and confirmed Kafka publication |
| `transaction-processor` | Kafka consumption, validation, and idempotent ledger persistence |
| `payment-e2e-tests` | Black-box HTTP-to-ledger tests of the packaged applications |

See [project state](docs/PROJECT_STATE.md), the
[architecture](docs/architecture/real-time-payment-processing-platform.md), and
[intake ADR](docs/adr/0001-transaction-intake-contract.md).

## Local infrastructure

Prerequisites: Git and Docker with Linux containers, BuildKit and Docker Compose v2
(supporting `up --wait`). Allow at least 4 GB of Docker memory and network access to
Docker Hub/Maven Central for the first build. No host Java or Maven is needed for
the containerized stack. From a clean clone:

```sh
git clone https://github.com/Abderraouf1990/realtime-payment-platform.git
cd realtime-payment-platform
git checkout codex/build-mvp
docker compose up --build -d --wait --wait-timeout 180
```

After the initial build, use:

```sh
docker compose up -d
docker compose ps
docker compose logs -f kafka
docker compose down
```

Compose now starts the API, processor, Kafka 4.1.1 in single-node KRaft mode,
PostgreSQL 17.6 and a one-shot topic initializer. The applications wait for successful
topic initialization, and the processor also waits for a healthy database. Check
`docker compose ps -a`: kafka-init must exit with code 0. API health is checked via
Actuator; the processor has no HTTP server and its running state does **not** prove
listener health. The acceptance exercise below verifies actual consumption.
Stop following logs with Ctrl+C; this does not stop Kafka.
Ports bind to loopback: API at `localhost:8080` (`API_PORT`), Kafka at `localhost:9092`
and PostgreSQL at `localhost:5432`. The database and user default to `payments`; the password
`payments_dev_only` is exclusively for local development.

All services share a dedicated `payments` bridge network. Kafka advertises
`localhost:9092` to host clients and `kafka:29092` inside that network. Named volumes
`kafka-data` and `postgres-data` retain data across `docker compose down` / `up`.
Compose prefixes network and volume names with the project name. Keep the Kafka
cluster ID unchanged when reusing its volume. PostgreSQL initialization variables
apply when its data volume is empty; editing them does not update existing database
users or passwords.

Optionally copy `.env.example` to `.env` and adjust the development values:

```powershell
Copy-Item .env.example .env
```

Compose reads `.env` automatically. Application containers always use Kafka's internal
address `kafka:29092` and the processor uses `postgres:5432`; published host ports do
not change these addresses. Only the processor receives database configuration.
Images contain no deployment credentials; development defaults in the application
configuration are not production secrets. Supply credentials at runtime for any
non-development deployment. The Docker build context excludes `.env`, Git data,
tests and host build artifacts via an allowlist.

For the optional host-development workflow below, Spring does not automatically
read `.env`. Export matching values in your application's shell or IDE, for example:

```powershell
$env:KAFKA_PORT = '19092'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:19092'
$env:POSTGRES_PORT = '15432'
$env:POSTGRES_PASSWORD = 'another_dev_only_password'
```

The processor also accepts `POSTGRES_HOST`, `POSTGRES_DB`, and `POSTGRES_USER`.
Only the processor connects to PostgreSQL; the API uses Kafka only.
`KAFKA_BOOTSTRAP_SERVERS` overrides the host Kafka address in both applications.

Topic auto-creation is disabled. `kafka-init` provisions `transactions.received` and
`transactions.rejected` idempotently with three partitions and one replica each.
Override their names with `TRANSACTIONS_RECEIVED_TOPIC` and `TRANSACTIONS_REJECTED_TOPIC`.
Inspect provisioning or rerun it after changing the configuration:

```sh
docker compose logs kafka-init
docker compose run --rm kafka-init
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list
```

To develop on the host instead, stop the application containers first and keep only
the infrastructure running (requires host JDK 25):

```powershell
docker compose stop transaction-api transaction-processor
docker compose up -d kafka postgres kafka-init
.\mvnw.cmd -pl shared-contracts -am install
.\mvnw.cmd -pl transaction-api spring-boot:run
# In another terminal:
.\mvnw.cmd -pl transaction-processor spring-boot:run
```

The processor applies Flyway migrations before consuming events. Its group defaults
to `transaction-processor-local` (`KAFKA_CONSUMER_GROUP`), and its topic defaults to
`transactions.received` (`TRANSACTIONS_RECEIVED_TOPIC`). Coordinate topic overrides
with topic provisioning. Compose supplies the API's `PAYMENTS_KAFKA_RECEIVED_TOPIC`;
host execution can override its `payments.kafka.received-topic` property.
The Testcontainers intake demo below starts only the API and its own broker; use
the full Compose stack for the complete flow.

### Container acceptance exercise (M1)

From PowerShell 5.1+ or PowerShell 7, run:

```powershell
.\scripts\verify-compose.ps1
```

The script builds from sources and creates a unique disposable Compose project with
random host ports. It checks UID 10001 for both applications, HTTP 202, one ledger row,
an identical retry processed as DUPLICATE without mutation, a negative-amount rejection
only in the audit table, and the keyed version-1 Kafka rejection notification. It then
performs down/up without deleting volumes, verifies retained ledger/audit data and a
new accepted payment. Its own containers and volumes are removed in finally, including
on failure; your normal Compose project is not touched. Build cache/images can remain.

Inspect the regular stack with `docker compose logs -f transaction-api transaction-processor`
and `docker compose exec postgres psql -U payments -d payments` (adapt credentials if
overridden). Compare ledger_transactions and transaction_rejections while submitting
the same example request twice. A 202 is Kafka intake confirmation, not ledger completion.

Multi-stage Dockerfiles pin Temurin 25 build/runtime bases by digest and keep only the
JRE/JAR in the final image. Applications use UID/GID 10001, a read-only root filesystem,
writable /tmp, dropped capabilities and no-new-privileges. Packaging skips tests inside
Docker; validation remains `.\mvnw.cmd clean verify` plus the Compose acceptance script.
The tradeoff is a larger Ubuntu JRE than distroless, with useful diagnostic tools for
this learning milestone. Listener-state probes, image scans, SBOMs and image publication
belong to later roadmap milestones; no outbox or automatic recovery has been added.
See [ADR 0007](docs/adr/0007-containerized-local-platform.md).

## Kafka processing and acknowledgement

The listener uses the shared `TransactionReceived` contract. JSON deserialization
selects that class explicitly and ignores Java type headers. The processor checks
schema version 1, identity fields and timestamp before invoking pure `TransactionRules`:
amount must be strictly positive, currency exactly `EUR` (no normalization), and
type `TRANSFER`. Missing business values also fail their corresponding rule.
All violated rules are collected in a stable order. `ProcessingResult` explicitly
distinguishes `Accepted` (inserted or duplicate) from `Rejected` (reason codes).
New events rejected by rules never write to the ledger. Accepted new amounts must additionally
fit NUMERIC(17,2); invalid representation remains a contract error.

Rejections log only transaction ID, correlation ID, and reason codes:
`AMOUNT_NOT_POSITIVE`, `CURRENCY_NOT_EUR`, `TYPE_NOT_TRANSFER` (or `PAYLOAD_CONFLICT`).
The application persists every business rejection in `transaction_rejections` through
a dedicated transactional JDBC adapter. After its commit, the processor publishes
`TransactionRejected` and waits for broker confirmation before the listener logs and
returns normally, allowing RECORD acknowledgement. A database or publication
failure stops consumption without acknowledging the failed event; correct the failure
and restart to replay. No retry topic or DLQ is introduced.
See [ADR 0005](docs/adr/0005-durable-business-rejections.md).

`ProcessTransaction` first reads the payload by transactionId after envelope validation.
An equal existing payload returns DUPLICATE; a difference on accountId, amount,
currency or type returns a business rejection with `PAYLOAD_CONFLICT`, even when
the changed value (for example USD) violates a new-transaction rule. Amounts compare
numerically (`10.0` equals `10.00`); the other fields compare exactly. Correlation
and timestamps are excluded. If no row exists, rules run before any write.

For new valid events it maps the event to a ledger entry and supplies `processed_at`
from an injected UTC clock. A transactional JDBC adapter inserts it using
`ON CONFLICT ON CONSTRAINT uk_ledger_transactions_transaction_id DO NOTHING`.
The PostgreSQL unique constraint is the final guarantee even if another writer
inserts after the lookup. Only this constraint's conflict enters duplicate/payload-conflict handling; other
database errors propagate. Identical business payloads are successful
duplicates; differences in account, amount, currency, or type return CONFLICT.
The same comparison runs after a skipped insert to handle an intervening committed
insert. No row is replaced and no second row is created.
Correlation and timestamps are metadata: duplicates retain the first stored values,
while processing logs include each incoming correlation ID. Logs exclude account
data, amounts, raw JSON, and underlying exception details.

Kafka auto-commit is disabled. Spring Kafka `RECORD` acknowledgement commits the
offset synchronously after the listener returns successfully, which happens only
after the JDBC transaction has committed (or an identical duplicate was verified)
for accepted events. Business rejections require committed audit persistence followed
by confirmed rejection publication as well.
Delivery is **at least once**: a crash between database commit and offset commit
replays the event, and the unique constraint prevents a second ledger entry.
There is no distributed Kafka/database transaction or exactly-once claim.

Technical, contract, or deserialization failures stop the listener without recovering,
skipping, or acknowledging the failed record. There are no retry topics or DLQ.
Correct the underlying problem and restart the processor to replay from the last
committed offset. The process itself may remain running with its listener stopped;
a malformed event requires operator intervention and will
block consumption again on restart. Automated recovery and listener-health
monitoring are not implemented. A new group starts at the earliest retained event.
See [ADR 0002](docs/adr/0002-ledger-consumption.md).

Payload conflicts are logged as `transactionId=... correlationId=... reason=PAYLOAD_CONFLICT`
using the incoming correlation ID, then acknowledged by normal listener return.
The existing row remains entirely unchanged; the incoming conflicting payload is
persisted in the rejection table before acknowledgement. Database read/commit failures
remain unacknowledged. An absent-ID lookup does not reserve the ID for a rejected
invalid event; see [ADR 0004](docs/adr/0004-payload-conflicts.md) for concurrency limits.

## Rejection notifications

`shared-contracts` defines `contracts.v1.TransactionRejected` with `schemaVersion=1`,
`transactionId`, `correlationId`, `reasonCodes` (an array of stable strings) and
`rejectedAt` (UTC ISO-8601). Reason codes currently are `AMOUNT_NOT_POSITIVE`,
`CURRENCY_NOT_EUR`, `TYPE_NOT_TRANSFER` and `PAYLOAD_CONFLICT`; consumers should tolerate
additional codes. No account data, amount or stack trace is published.

The processor publishes JSON without Java type headers, keyed by `transactionId`,
to `payments.kafka.rejected-topic` (environment `TRANSACTIONS_REJECTED_TOPIC`, default
`transactions.rejected`). It uses `acks=all`, producer idempotence and a bounded wait
(`payments.kafka.publish-timeout`, default 10s). These are business outcome notifications,
not a retry topic or DLQ. Accepted transactions emit no rejection notification.

**Without an outbox, PostgreSQL persistence and Kafka publication are not atomic.**
The application service has no enclosing database transaction: the store's transaction
commits before the publisher is called. Publication failure leaves the audit committed
but input unacknowledged; restore service and manually restart the listener/process.
Replay attempts publication even when the audit row already exists. A crash after
publication but before input offset commit, or an uncertain send timeout, can produce
duplicate notifications. Producer idempotence does not deduplicate application replays.
An audit row alone does not prove publication, and no background audit scan repairs it.
Recovery depends on retained input and replay; there is no exactly-once guarantee.

Each notification describes the current rejected processing attempt: incoming correlation,
current reason codes and evaluation time. On retries these may differ from the first
immutable audit row. `transactionId` is a partition key, not a unique notification ID;
different conflicting payloads may share it. See [ADR 0006](docs/adr/0006-rejection-notifications.md).

## Ledger schema

The processor already includes `spring-boot-starter-flyway`,
`flyway-database-postgresql`, and the PostgreSQL JDBC driver (runtime scope).
Flyway discovers `src/main/resources/db/migration/V1__create_ledger_transactions.sql`
and creates `ledger_transactions` in the connection's default application schema
(`public` with the supplied local setup). The SQL does not hard-code a schema.

| Column | PostgreSQL type | Constraint |
| --- | --- | --- |
| `id` | `BIGINT GENERATED ALWAYS AS IDENTITY` | Primary key |
| `transaction_id` | `VARCHAR(64)` | Not null, unique |
| `correlation_id`, `account_id` | `VARCHAR(64)` | Not null |
| `amount` | `NUMERIC(17,2)` | Not null |
| `currency` | `VARCHAR(3)` | Not null |
| `type` | `VARCHAR(32)` | Not null |
| `received_at`, `processed_at` | `TIMESTAMPTZ` | Not null |

`processed_at` is supplied by the processor when recording the result.
Timestamps represent instants; PostgreSQL retains microsecond precision, not the
original timezone offset. The processor accepts only positive EUR transfers.

The primary key and unique constraint supply their own indexes. The only additional
index is `(account_id, received_at DESC)` for account-history queries. No duplicate
index on `transaction_id` or speculative per-column indexes are added.

The unique constraint prevents duplicate transaction IDs at the database boundary;
the consumer also compares duplicate business payloads before acknowledging them.
Once applied, keep V1 unchanged and introduce subsequent schema changes as V2, V3, etc.

## Rejection audit schema

Flyway V2 creates `transaction_rejections` with generated BIGINT `id`, required
transaction/correlation/account IDs, `amount NUMERIC`, `currency TEXT`, `type VARCHAR(32)`,
required `received_at`/`rejected_at TIMESTAMPTZ`, and nonempty `reason_codes TEXT[]`.
Missing business values are stored as NULL and rejected decimals are not rounded.
No stack trace or technical exception message is stored.

A PostgreSQL unique constraint with NULLS NOT DISTINCT covers transaction_id,
account_id, amount, currency and type. Identical business payloads create one audit
row, even if correlation or timestamps change; amounts compare numerically. First
metadata and reason codes are retained. A different business payload creates its
own rejection row. The unique index supports transaction_id lookup; a separate
rejected_at index supports time-based audit queries.

Accepted events write only to the ledger; rejected events write only to the audit
table. A conflict preserves the previous accepted ledger row. A corrected valid
payload can later use an ID seen in a rejection, so the two tables may contain the
same transaction_id for different payloads. This audit records first rejections,
not every Kafka delivery. Retention, audit access control and a query API remain
future work. A replay after DB commit is deduplicated; no exactly-once claim is made.

## Run the intake demo

Prerequisites: JDK 25 (`JAVA_HOME` configured), Docker with Linux containers, and
Maven/image registry access for dependencies not already cached.

From the repository root in PowerShell:

```powershell
.\mvnw.cmd -pl shared-contracts -am install
.\mvnw.cmd -pl transaction-api spring-boot:test-run
```

The test launcher starts `apache/kafka:4.1.1`, provisions
`transactions.received` with three partitions and one replica, and starts the API
on port 8080. These disposable containers are for local development only.

To use an existing broker instead, provision `transactions.received`, set
`KAFKA_BOOTSTRAP_SERVERS`, and run `spring-boot:run` instead of `spring-boot:test-run`.
The default bootstrap address is `localhost:9092`. The topic name can be overridden
with the `payments.kafka.received-topic` Spring property.

```powershell
$transactionBody = @{
    transactionId = 'TX-2026-0001'
    correlationId = 'CORR-2026-0001'
    accountId = 'ACC-100'
    amount = 250.00
    currency = 'EUR'
    type = 'TRANSFER'
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/transactions' `
    -Headers @{ 'Idempotency-Key' = 'TX-2026-0001' } `
    -ContentType 'application/json' -Body $transactionBody
```

Successful publication returns HTTP 202 and
`{"transactionId":"TX-2026-0001","correlationId":"CORR-2026-0001"}`.
This confirms intake into Kafka, not ledger completion. There is no status-query
endpoint yet.

## Request and error contract

- All six request fields are required. IDs accept 1–64 ASCII letters, digits,
  underscores, or hyphens. `Idempotency-Key` must equal `transactionId`.
- Supply `correlationId` in the JSON body to identify the originating workflow.
  The API preserves it in `TransactionReceived` and the acceptance response. Reuse
  it on retries and propagate it unchanged through downstream services and events.
  It is separate from the idempotency key and may be shared by related transactions.
  Missing, blank, or malformed correlation IDs return 400 without publication.
- `amount` is decimal with at most 15 integer and 2 fractional digits. Currency is
  three uppercase letters; `TRANSFER` is the only current transaction type.
- The processor accepts only positive EUR transfers. A structurally valid negative
  amount or non-EUR currency can receive 202 at intake, then be logged as rejected
  and persisted as a rejection before acknowledgement, without a ledger entry.
- The shared enum currently contains only `TRANSFER`. A missing type is a business
  rejection; an unknown JSON enum value fails deserialization and stops the consumer.
- Retry with the same ID, key, and payload. Retries may publish multiple events.
  Reusing an ID with a changed payload is not detected across requests yet.

| Status | Meaning |
| --- | --- |
| 202 | Kafka confirmed publication; response contains `transactionId` and `correlationId` |
| 400 | Missing/malformed header, malformed JSON, or invalid request fields |
| 409 | Well-formed `Idempotency-Key` differs from the body `transactionId` |
| 415 | Unsupported request content type |
| 503 | Publication could not be confirmed; outcome may be uncertain |

Error bodies use `application/problem+json` without including rejected field values
or internal failure details. This local demo does not yet implement authentication.

## Tests

Run unit and MVC tests only (Surefire, no Docker required):

```powershell
.\mvnw.cmd test
```

Run the full build, unit tests and all integration tests (Docker required):

```powershell
.\mvnw.cmd clean verify
```

On Linux/macOS, use `./mvnw clean verify`. All container-backed tests use the `*IT`
suffix and Maven Failsafe's `integration-test` / `verify` goals. Unit `*Tests` remain
in Surefire. Reports are in each module's `target/surefire-reports` and
`target/failsafe-reports`. GitHub Actions already runs `clean verify` and uploads
both report directories on failure, so no workflow change is necessary.

The dedicated `payment-e2e-tests` module depends on the two application artifacts
for reactor ordering and runs their executable JARs in separate JVMs. The API's
runtime classpath has no PostgreSQL dependency. It sends real HTTP requests to a
random port through Kafka into PostgreSQL, using `apache/kafka:4.1.1` and
`postgres:17.6`. Its scenarios cover insertion, an identical HTTP retry, a payload
conflict, negative amount, non-EUR currency, and PostgreSQL server unavailability.
The outage stops the disposable PostgreSQL server, confirms HTTP intake still
succeeds and the failed event remains unacknowledged, then restarts the database
and processor to prove successful replay. This exercises the existing manual
restart strategy, not automatic retries. Short connection timeouts are test-only.
Application logs are retained in `payment-e2e-tests/target/failsafe-reports`.
`PaymentFlowIT` has five independent scenarios with shared setup/cleanup code and
fresh containers and application processes per test. No scenario depends on test
ordering or another test's rows/offsets. Logs are grouped by test method. Cleanup
attempts to close every registered resource even after partial startup or test failure.

Run the end-to-end test and its required reactor build:

```powershell
.\mvnw.cmd -pl payment-e2e-tests -am '-Dit.test=PaymentFlowIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

Run the PostgreSQL migration integration tests (Docker required; no Kafka needed):

```powershell
.\mvnw.cmd -pl transaction-processor -am '-Dit.test=LedgerMigrationIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

The pinned `postgres:17.6` container proves Flyway V1 application and repeat-run
behavior, the default-schema table and unique constraint, generated IDs, exact
amount/timestamp storage, duplicate rejection (`23505`), and null-ID rejection (`23502`).
Run all processor tests, including Spring startup and Kafka-to-ledger integration, with:

```powershell
.\mvnw.cmd -pl transaction-processor -am verify
```

The Kafka + PostgreSQL tests check persisted fields and correlation logs and ignore
spoofed Java type headers. A dedicated test publishes exactly the same event twice,
waits for both offsets to be committed, and verifies exactly one ledger row plus
INSERTED/DUPLICATE outcomes with the listener still running.
A deferred constraint trigger injects a PostgreSQL failure at COMMIT, after INSERT
succeeds, proving rollback with no offset advancement before successful replay on
listener restart. This tests a database commit failure, not a network outage.
Amount and currency conflicts are logged and acknowledged without changing the row,
and the listener continues. Direct PostgreSQL tests verify each compared field,
identical duplicates, metadata-only differences and preservation of all row columns.
Application unit tests cover mapping, validation, and storage-error propagation.
Rule tests cover positive/zero/negative amounts, exact currency matching, missing
values, and all eight valid/invalid combinations of the three business rules.
The Kafka test also verifies that a combined rejection creates no ledger row,
advances the offset, logs both IDs and reason codes without account data, and leaves
the listener able to process subsequent events.
Concurrent duplicate delivery and a process crash between database commit and
Kafka offset commit are not directly tested. The guarantees are at-least-once
consumption and idempotent ledger persistence, not exactly-once processing.

Run focused unit and MVC tests without Docker:

```powershell
.\mvnw.cmd '-pl=transaction-api,transaction-processor' -am '-Dtest=ReceiveTransactionTests,TransactionControllerTests,KafkaTransactionPublisherTests,TransactionReceivedContractTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Run the Kafka publication integration test with Docker:

```powershell
.\mvnw.cmd -pl transaction-api -am '-Dit.test=TransactionPublicationIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

The repository's complete validation command remains `.\mvnw.cmd clean verify`.
Container-backed `*IT` tests run through Failsafe, after packaging the applications.
Kafka is pinned to `4.1.1` and PostgreSQL to `17.6`.

Testcontainers uses the JVM image `apache/kafka:4.1.1`, matching Compose.
The native image crashed during setup on the GitHub Ubuntu runner in
[run 35428956254](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35428956254/job/105863836497).
Both variants are supported by the
[Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/).
Spring Boot manages JUnit Jupiter 6.0.1, as required by Spring Framework 7.

Rejection audit integration tests include V1-to-V2 migration, null/decimal preservation,
idempotence and a rejection COMMIT failure with no premature Kafka acknowledgement.
Automated recovery, audit access control and operational dashboards remain future work.
