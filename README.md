# Realtime Payment Platform

A Java 25 / Spring Boot 4.0.1 payment-processing demonstrator with the intended flow:

`Transaction API -> Kafka -> Transaction Processor -> PostgreSQL`

The API intake slice and the ledger schema migration are implemented. The processor
does not yet consume events or write ledger entries; end-to-end idempotency remains pending.

## Modules

| Module | Responsibility |
| --- | --- |
| `shared-contracts` | Framework-free versioned events and shared value types |
| `transaction-api` | HTTP structural validation and confirmed Kafka publication |
| `transaction-processor` | Future deterministic validation and idempotent ledger persistence |

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

The processor applies its Flyway migrations on startup but has no consumer or ledger
writer yet. The Testcontainers demo below is an alternative to Compose.

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

`processed_at` must be supplied by the future processor when recording the result.
Timestamps represent instants; PostgreSQL retains microsecond precision, not the
original timezone offset. Amount positivity and supported-currency rules remain
part of future business validation.

The primary key and unique constraint supply their own indexes. The only additional
index is `(account_id, received_at DESC)` for account-history queries. No duplicate
index on `transaction_id` or speculative per-column indexes are added.

The unique constraint prevents duplicate transaction IDs at the database boundary;
consumer retries, conflict handling, and acknowledgement ordering are not implemented.
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
- Sign checks and supported-currency business rules belong to the future processor.
  A structurally valid negative amount can therefore receive 202 at intake.
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
Run all processor tests, including Spring startup, with:

```powershell
.\mvnw.cmd -pl transaction-processor -am test
```

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

Ledger writing, consumer recovery tests, and operational dashboards remain future work.
