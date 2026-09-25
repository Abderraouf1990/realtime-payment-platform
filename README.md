# Realtime Payment Platform

A Java 25 / Spring Boot 4.0.8 payment-processing demonstrator with the intended flow:

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
Actuator. Source-built processors now expose listener health on container loopback
8081, without a published Compose port or Compose healthcheck; published M2 images
still have no HTTP server. A running process alone does **not** prove listener health.
The acceptance exercise below verifies actual consumption.
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
block consumption again on restart. Automated recovery is not implemented.
Source-built processors expose listener health as described in the M4 section below;
alerting is still pending. A new group starts at the earliest retained event.
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
Spring Boot manages JUnit Jupiter 6.0.3, as required by Spring Framework 7.

Rejection audit integration tests include V1-to-V2 migration, null/decimal preservation,
idempotence and a rejection COMMIT failure with no premature Kafka acknowledgement.
Automated recovery, audit access control and operational dashboards remain future work.

## Local Kubernetes (M3)

The [Helm chart](deploy/helm/payments) runs the M2 application images by verified
GHCR digest, Kafka 4.1.1 and PostgreSQL 17.6. See
[ADR 0009](docs/adr/0009-local-kubernetes.md) for security, probes and recovery limits.
No application rebuild or image publication is needed.

Prerequisites on Windows: Docker Desktop with Linux containers (the validation
machine provides about 8 GB RAM), PowerShell, Helm 3 and kubectl compatible with
Kubernetes 1.34. Allow disk space for the node and application images. The script
downloads kind 0.33.0 to ignored `artifacts/tools` and verifies its SHA-256; the
node image is pinned to Kubernetes 1.34.11 by digest. Internet access is needed
for the first downloads, including the public GHCR images.

From the repository root:

```powershell
# Complete isolated acceptance exercise, with automatic cleanup on success/failure:
.\scripts\verify-kubernetes.ps1

# Keep the cluster for hands-on inspection (also retained if a check fails):
.\scripts\verify-kubernetes.ps1 -KeepCluster
```

The script prints the unique cluster name and private kubeconfig path. It creates
an enforced Pod Security `restricted` namespace, injects a generated development
database Secret through stdin, runs server-side admission checks and installs
the chart with `helm install --wait --wait-for-jobs`. It does not change the
existing Kubernetes context. Its loopback port-forward is stopped on exit.
Raw local evidence stays in the ignored `artifacts/<cluster-name>/` directory;
do not publish its kubeconfig.

Acceptance covers HTTP to ledger, identical retry, conflict without mutation,
negative amount and non-EUR rejection, Kafka rejection notifications, non-root
runtime identity and restricted application settings. It then upgrades the
`rolloutMarker` annotation, processes a payment, rolls back and processes another.
Finally it recreates the Kafka/PostgreSQL pods, checks PVC retention, manually
restarts the processor and verifies another payment. The deployments use Recreate
and incur downtime. This is configuration rollback with unchanged image digests,
not a database migration or application-version rollback.

For the reproducible learning exercise, retain the cluster and copy its printed
name/path into the following variables (all commands explicitly target it):

```powershell
$clusterName = '<printed-cluster-name>'
$localKubeconfig = '<printed-kubeconfig-path>'
$localContext = "kind-$clusterName"
kubectl --kubeconfig $localKubeconfig --context $localContext -n payments get pods,pvc
helm --kubeconfig $localKubeconfig --kube-context $localContext -n payments history payments
helm --kubeconfig $localKubeconfig --kube-context $localContext -n payments upgrade payments ./deploy/helm/payments --set-string rolloutMarker=manual --wait --wait-for-jobs
helm --kubeconfig $localKubeconfig --kube-context $localContext -n payments rollback payments 1 --wait --wait-for-jobs
kubectl --kubeconfig $localKubeconfig --context $localContext -n payments port-forward service/payments-transaction-api 8080:8080 --address 127.0.0.1
# Ctrl+C stops the foreground port-forward. Cleanup deletes this cluster's data:
& .\artifacts\tools\kind.exe delete cluster --name $clusterName --kubeconfig $localKubeconfig
```

API probes report Actuator liveness/readiness, PostgreSQL probes server availability
and Kafka readiness executes a broker request. The published processor has no
listener-health endpoint; a Ready pod is not proof that its consumer is running.
After a technical failure, repair the dependency and explicitly run
`kubectl ... rollout restart deployment/payments-transaction-processor`, using
the same kubeconfig/context/namespace arguments above. M4 adds listener monitoring.
Application filesystems are read-only; Kafka/tool initialization retains a writable
image filesystem. The chart uses an existing Secret (`database`, `username`,
`password` keys); only the processor receives database credentials. This single-node
exercise provides neither high availability nor network isolation or TLS. PVCs
survive pod recreation, but deleting kind deletes their data.

## Listener health and PostgreSQL incident (M4, first slice)

Newly built processor JARs expose Actuator health and read-only metrics, by default at
`127.0.0.1:8081` (`PROCESSOR_ADDRESS` / `PROCESSOR_PORT`). The published M2
image digests still contain the previous non-web processor. No new image has
been published. See [ADR 0010](docs/adr/0010-processor-listener-health.md).

```powershell
curl.exe -i http://127.0.0.1:8081/actuator/health/listener
curl.exe -i http://127.0.0.1:8081/actuator/health/readiness
curl.exe -i http://127.0.0.1:8081/actuator/health/liveness
```

Listener health exposes only STARTING, RUNNING, STOPPED, MISSING or PAUSED.
After a processing failure, listener/readiness return 503 while liveness remains
200, allowing inspection without automatic replay. Restore the dependency, then
restart the processor manually. UP means the listener lifecycle is running;
it does not prove database availability, broker connectivity, assigned partitions
or processing progress. Idle database outages are detected on processing failure.

The [PostgreSQL incident runbook](docs/runbooks/processor-postgresql-outage.md)
contains the automated real-JAR exercise, a manual local exercise and the probe
policy. Helm has opt-in `processor.healthProbes.enabled=true` for a new M4 image;
it remains false for default M2 images. Startup/liveness use only the liveness
group; readiness includes the listener. Runtime Kubernetes validation of this
opt-in configuration is separate from the existing M3 acceptance evidence.

```powershell
.\mvnw.cmd -B -ntp -pl payment-e2e-tests -am '-Dit.test=PaymentFlowIT#databaseOutageLeavesOffsetUncommittedUntilManualRestartAndReplayIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
.\mvnw.cmd clean verify
```

The processor now writes structured JSON processing logs with `event=payment.processing`,
validated transaction/correlation IDs, outcome, reasonCodes, partition and offset.
No account, amount, raw event or exception is attached to these processing records.
`payments.processing.attempts` has only four `outcome` series: accepted, duplicate,
rejected and technical_failure. IDs and reasons are never labels on this metric.

```powershell
curl.exe -s http://127.0.0.1:8081/actuator/metrics/payments.processing.attempts
curl.exe -s 'http://127.0.0.1:8081/actuator/metrics/payments.processing.attempts?tag=outcome:technical_failure'
```

These are per-JVM attempts before Kafka acknowledgement, not unique transactions
or durable audit totals. Replays can increment again; restart resets counters.
Deserialization/poll failures before the listener and offset-commit failures are
outside this metric. Recording failures must not change payment processing.
See [ADR 0011](docs/adr/0011-processing-logs-and-attempt-metrics.md) and the runbook
for the precise limits. Built-in framework metrics are also visible on this local
management surface; no collector or external exposure is introduced.

New source builds propagate W3C trace context from HTTP through Kafka to each
processor attempt and rejection publication. Processor JSON logs include scoped
traceId/spanId independently of business correlationId. Sampling defaults to 10%;
export defaults to disabled (`TRACING_EXPORT_ENABLED`, `TRACING_SAMPLE_PROBABILITY`,
`TRACING_ENDPOINT`). See [ADR 0012](docs/adr/0012-http-kafka-tracing.md) and the
[local trace exercise](docs/runbooks/http-kafka-tracing.md), including exporter
failure and manual replay. Published M2 images do not include this instrumentation.

M4 remains in progress: progress/lag metrics, collection, alerting,
dashboards and the remaining incident exercises are still pending.

## Secure image delivery (M2)

CI now prepares tested, commit-labelled application images, CodeQL Java
`security-extended` analysis, Trivy secret/OS/JAR scans and CycloneDX SBOMs.
The delivery policy blocks every code/secret finding, HIGH/CRITICAL vulnerability
(even without a fix), failed check or unavailable scanner. No exceptions are
configured. See [ADR 0008](docs/adr/0008-secure-image-delivery.md) for coverage,
permissions, artifact handling and limitations, and
[PROJECT_STATE](docs/PROJECT_STATE.md) for actual validation results.

The dependency remediation uses Boot 4.0.8's BOM plus a targeted Tomcat 11.0.26
override; see the rationale and override removal condition in ADR 0008. Kafka
clients are now 4.1.2 while the pinned local/test broker remains 4.1.1. The local
image scans pass the blocking policy; remaining MEDIUM/LOW findings are visible
in the reports. [CI run 35653382625](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35653382625)
passed for commit cca00ee, including CodeQL, Compose, image/secret gates and SBOMs.
The publication job was skipped on that push. After explicit approval,
[manual run 35661616528](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35661616528)
repeated every gate and published both images. Their remote manifests/configs,
revision labels and non-root user match the tested bundle. M2 is complete for cca00ee;
[publication evidence](docs/evidence/m2-publication.json) records both complete
image references, registry digests and image IDs for reuse by M3.

Run policy tests and a checkout secret scan from the repository root
(Python 3.11+, PowerShell and Docker):

```powershell
python -m unittest discover -s scripts -p 'test_*.py' -v
.\scripts\security-scan.ps1 -SourceOnly
```

For a locally built image, run the same security check and generate its SBOM:

```powershell
.\scripts\security-scan.ps1 -Image realtime-payment-platform-transaction-api -OutputDirectory artifacts/images/transaction-api
```

Use the tag shown by `docker compose images` if a custom Compose project name
was used. Reports are under `artifacts/` (ignored by Git). The command fails when
the policy blocks the image but still generates its SBOM if the scanner succeeds.
No scan result is a guarantee that an image has no vulnerabilities.

CI exercises its already-built images with `scripts/verify-compose.ps1 -ApiImage
<api-tag> -ProcessorImage <processor-tag>`, then preserves those exact archives and
SBOM hashes. Normal pushes and PRs never publish. After explicit owner approval,
select the CI workflow's manual dispatch on an allowed branch, set `publish=true`
and provide the selected ref's full SHA as `approved_sha`. The workflow must be
present on the default branch for manual dispatch to be available. A wrong SHA
skips publication. All checks must pass in that run before GHCR write access is
used; no new secret/PAT is needed.

Successful publication records registry digests in the `published-images` run
artifact. Tags are `ghcr.io/<owner>/<repository>-transaction-api:sha-<full-sha>`
and the equivalent `-transaction-processor` tag. Use the recorded digest for an
immutable image reference. Publication of the pair is not atomic, and the SBOMs
are workflow artifacts rather than signed OCI attestations. After an approved manual
dispatch, compare `published-images` digests with the registry, then compare the
remote image config IDs with `manifest.json` in that run's verified bundle and
check the revision labels. Archive hashes and registry digests are different values.
The manual run repeats all validation jobs and
promotes its own archives. Repeat this verification for each newly authorized delivery.
