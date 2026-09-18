# Realtime Payment Platform

A Java 25 / Spring Boot 4.0.1 payment-processing demonstrator with the intended flow:

`Transaction API -> Kafka -> Transaction Processor -> PostgreSQL`

The API publishes events and the processor consumes them into the PostgreSQL ledger,
with database-backed deduplication and commit-before-acknowledgement ordering for
accepted transactions. Business rejections are temporarily logged and acknowledged.

## Modules

| Module | Responsibility |
| --- | --- |
| `shared-contracts` | Framework-free versioned events and shared value types |
| `transaction-api` | HTTP structural validation and confirmed Kafka publication |
| `transaction-processor` | Kafka consumption, validation, and idempotent ledger persistence |

See [project state](docs/PROJECT_STATE.md), the
[architecture](docs/architecture/real-time-payment-processing-platform.md), and
[intake ADR](docs/adr/0001-transaction-intake-contract.md).

## Local infrastructure

From the repository root, with Docker running in Linux-container mode:

```sh
docker compose up -d
docker compose ps
docker compose logs -f kafka
docker compose down
```

Wait for both services to become `healthy` in `docker compose ps` before starting
the applications. Stop following logs with Ctrl+C; this does not stop Kafka.
Compose runs only Kafka 4.1.1 in single-node KRaft mode and PostgreSQL 17.6.
Ports bind to the host loopback interface: Kafka at `localhost:9092` and PostgreSQL
at `localhost:5432`. The database and user default to `payments`; the password
`payments_dev_only` is exclusively for local development.

Both services share a dedicated `payments` bridge network. Kafka advertises
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

Compose reads `.env` automatically; host Spring Boot applications do not. Defaults
already match, so no exports are necessary for the standard setup. For custom
values, set the matching environment variables in each application's shell or IDE.
For example, if you changed the ports and password in `.env`:

```powershell
$env:KAFKA_PORT = '19092'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:19092'
$env:POSTGRES_PORT = '15432'
$env:POSTGRES_PASSWORD = 'another_dev_only_password'
```

The processor also accepts `POSTGRES_HOST`, `POSTGRES_DB`, and `POSTGRES_USER`.
Only the processor connects to PostgreSQL; the API uses Kafka only.
`KAFKA_BOOTSTRAP_SERVERS` overrides the host Kafka address in both applications.

Topic auto-creation is disabled. After Kafka is healthy, provision the intake topic:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic transactions.received --partitions 3 --replication-factor 1
```

Run the applications on the host in separate terminals:

```powershell
.\mvnw.cmd -pl shared-contracts -am install
.\mvnw.cmd -pl transaction-api spring-boot:run
# In another terminal:
.\mvnw.cmd -pl transaction-processor spring-boot:run
```

The processor applies Flyway migrations before consuming events. Its group defaults
to `transaction-processor-local` (`KAFKA_CONSUMER_GROUP`), and its topic defaults to
`transactions.received` (`TRANSACTIONS_RECEIVED_TOPIC`). Coordinate topic overrides
with the API's `payments.kafka.received-topic` property and topic provisioning.
The Testcontainers intake demo below starts only the API and its own broker; use
Compose with both host applications for the complete flow.

## Kafka processing and acknowledgement

The listener uses the shared `TransactionReceived` contract. JSON deserialization
selects that class explicitly and ignores Java type headers. The processor checks
schema version 1, identity fields and timestamp before invoking pure `TransactionRules`:
amount must be strictly positive, currency exactly `EUR` (no normalization), and
type `TRANSFER`. Missing business values also fail their corresponding rule.
All violated rules are collected in a stable order. `ProcessingResult` explicitly
distinguishes `Accepted` (inserted or duplicate) from `Rejected` (reason codes).
Rejected events never call the ledger store. Accepted amounts must additionally
fit NUMERIC(17,2); invalid representation remains a contract error.

Rejections log only transaction ID, correlation ID, and reason codes:
`AMOUNT_NOT_POSITIVE`, `CURRENCY_NOT_EUR`, `TYPE_NOT_TRANSFER`. The listener returns
normally after logging, so RECORD acknowledgement advances the offset without a
ledger write. This temporary policy avoids repeated consumption of deterministic
business rejections. **There is no durable rejection record**: after offset commit,
the consumer group does not replay the rejection automatically; log retention is
not an audit guarantee. A crash before offset commit may repeat the rejection log.
No retry topic or DLQ is introduced. See [ADR 0003](docs/adr/0003-business-rejections.md).

`ProcessTransaction` maps the event to a ledger entry and supplies `processed_at`
from an injected UTC clock. A transactional JDBC adapter inserts it using
`ON CONFLICT ON CONSTRAINT uk_ledger_transactions_transaction_id DO NOTHING`.
The PostgreSQL unique constraint is the final guarantee, with no check-before-insert
race. Only this constraint's conflict is handled as an expected duplicate; other
database errors propagate. Identical business payloads are successful
duplicates; differences in account, amount, currency, or type fail processing.
Correlation and timestamps are metadata: duplicates retain the first stored values,
while processing logs include each incoming correlation ID. Logs exclude account
data, amounts, raw JSON, and underlying exception details.

Kafka auto-commit is disabled. Spring Kafka `RECORD` acknowledgement commits the
offset synchronously after the listener returns successfully, which happens only
after the JDBC transaction has committed (or an identical duplicate was verified)
for accepted events. Business rejections use the temporary logging policy above.
Delivery is **at least once**: a crash between database commit and offset commit
replays the event, and the unique constraint prevents a second ledger entry.
There is no distributed Kafka/database transaction or exactly-once claim.

Technical, contract, or deserialization failures stop the listener without recovering,
skipping, or acknowledging the failed record. There are no retry topics or DLQ.
Correct the underlying problem and restart the processor to replay from the last
committed offset. The process itself may remain running with its listener stopped;
a malformed event or conflicting duplicate requires operator intervention and will
block consumption again on restart. Automated recovery and listener-health
monitoring are not implemented. A new group starts at the earliest retained event.
See [ADR 0002](docs/adr/0002-ledger-consumption.md).

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

## Run the intake demo

Prerequisites: JDK 25 (`JAVA_HOME` configured), Docker with Linux containers, and
Maven/image registry access for dependencies not already cached.

From the repository root in PowerShell:

```powershell
.\mvnw.cmd -pl shared-contracts -am install
.\mvnw.cmd -pl transaction-api spring-boot:test-run
```

The test launcher starts `apache/kafka-native:4.1.1`, provisions
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
  and acknowledged without a ledger entry. There is no durable rejection outcome yet.
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

Run the PostgreSQL migration integration tests (Docker required; no Kafka needed):

```powershell
.\mvnw.cmd -pl transaction-processor -am '-Dtest=LedgerMigrationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The pinned `postgres:17.6` container proves Flyway V1 application and repeat-run
behavior, the default-schema table and unique constraint, generated IDs, exact
amount/timestamp storage, duplicate rejection (`23505`), and null-ID rejection (`23502`).
Run all processor tests, including Spring startup and Kafka-to-ledger integration, with:

```powershell
.\mvnw.cmd -pl transaction-processor -am test
```

The Kafka + PostgreSQL tests check persisted fields and correlation logs and ignore
spoofed Java type headers. A dedicated test publishes exactly the same event twice,
waits for both offsets to be committed, and verifies exactly one ledger row plus
INSERTED/DUPLICATE outcomes with the listener still running.
A deferred constraint trigger injects a PostgreSQL failure at COMMIT, after INSERT
succeeds, proving rollback with no offset advancement before successful replay on
listener restart. This tests a database commit failure, not a network outage.
A conflicting duplicate stops consumption without changing the existing row.
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
.\mvnw.cmd -pl transaction-api -am '-Dtest=TransactionPublicationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The repository's complete validation command remains `.\mvnw.cmd clean verify`.
Container-backed tests currently run through Surefire as `*Tests`; they are not
separated into a Failsafe phase. Kafka is pinned to `4.1.1` and PostgreSQL to `17.6`.
Spring Boot manages JUnit Jupiter 6.0.1, as required by Spring Framework 7, despite
the older JUnit 5 wording in the project instructions.

Durable rejection handling, automated recovery, and operational dashboards remain future work.
