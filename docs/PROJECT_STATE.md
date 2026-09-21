# Project State

Updated: 2026-09-21

## Current Implementation

- Maven application modules: `shared-contracts`, `transaction-api`, and `transaction-processor`.
  `payment-e2e-tests` runs the packaged applications in separate JVMs for black-box testing.
- Parent manages Java 25 and Spring Boot 4.0.8; Maven Wrapper pins Maven 3.9.0.
  Tomcat is explicitly upgraded from the BOM's 11.0.24 to 11.0.26 for security fixes.
- `shared-contracts` contains framework-free `contracts.v1.TransactionReceived`
  (including `correlationId`), `TransactionRejected` and `TransactionType` (`TRANSFER`).
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
  Rejections never write to the ledger. Flyway V2 adds `transaction_rejections`,
  storing audit fields and reason codes through a dedicated transactional port/adapter.
  After rejection persistence commits, a publisher port sends `TransactionRejected`
  keyed by transactionId to configurable `payments.kafka.rejected-topic` (default
  transactions.rejected). JSON has no Java type headers. Normal return/ack requires
  broker confirmation; database and publication errors stop consumption without
  advancing the offset. Persistence and publication are not atomic without an outbox;
  replay can duplicate notifications, even though the audit remains idempotent.
  PostgreSQL deduplicates transactionId + business payload, including null values;
  the first correlation, timestamps and reasons are retained.
  Explicit JSON deserialization ignores Java type headers. Consumer group and topic
  are configurable, with local defaults.
- Flyway's Boot starter, PostgreSQL database support, and PostgreSQL JDBC runtime
  driver were already declared; no duplicate dependencies or POM changes were needed.
- Ledger columns use a generated BIGINT primary key, a required unique transaction ID,
  required correlation/account IDs, NUMERIC(17,2), currency/type strings, and required
  TIMESTAMPTZ received/processed instants. Processing time is supplied by the writer.
  The only additional index is `(account_id, received_at DESC)` for account history.
- Container images are pinned: `apache/kafka:4.1.1` and `postgres:17.6`.
- Root `.gitignore`, README, architecture document, roadmap and versioned ADRs exist.
- Compose builds and runs both applications from sources through multi-stage Dockerfiles
  with digest-pinned Temurin 25 JDK/JRE bases. UID/GID 10001, read-only application root
  filesystems, /tmp tmpfs, dropped capabilities and no-new-privileges are configured.
  The build context allowlist excludes .env, Git metadata and host targets.
  Kafka 4.1.1 and PostgreSQL 17.6 retain their named volumes and loopback ports.
  API HTTP binds to loopback 8080 (API_PORT); internal connections use kafka:29092 and
  postgres:5432. Only the processor receives database settings. Topic initialization
  gates both applications and PostgreSQL health gates the processor.
  API Actuator health is checked; the non-web processor has no HTTP healthcheck.
  A running processor container is not a guarantee of listener health.
- GitHub Actions CI runs `./mvnw clean verify` on pushes and pull requests targeting
  `main` and `codex/build-mvp`, using Temurin 25, Maven caching, and read-only contents
  permissions. Surefire/Failsafe reports are uploaded only on failure.
- M2 workflow changes add CodeQL Java security analysis, checkout secret scanning,
  labelled image builds, Compose acceptance of those images, OS/JAR/secret scans and
  CycloneDX SBOMs. Actions/tools are pinned. Strict gates block every code/secret
  finding, HIGH/CRITICAL vulnerability and scanner failure; no exception is configured.
  Only an explicit manual dispatch for an approved full SHA can publish both verified
  image archives to GHCR. Only that job has packages:write. Hashes and registry digests
  provide traceability, not signing or atomic publication. See ADR 0008.

## Validated State

- 2026-09-21 (M2 dependency remediation, local worktree based on 2f50ec3): checked
  AGENTS.md/ROADMAP.md/state against the POMs, packaged dependencies, processor
  store-before-publish/ack ordering and CI gates. Updated the Boot parent to 4.0.8
  and Tomcat to 11.0.26; no Java business code, migrations, Kafka acknowledgement
  policy, scanner threshold or infrastructure image changed. Aligned current
  version references and the obsolete JUnit note in ADR 0001.
  The packaged applications contain Spring 7.0.9, Spring Kafka 4.0.7, Kafka clients
  4.1.2, Micrometer 1.16.7 and Jackson 3.1.5. The processor also contains PostgreSQL
  JDBC 42.7.13 and Jackson 2.21.5. Kafka clients now brings at.yawk.lz4:lz4-java
  1.10.1 instead of org.lz4:lz4-java 1.8.0. The API JAR contains no PostgreSQL,
  spring-jdbc or Hibernate core dependency. The Kafka broker remains pinned to 4.1.1.
  `.\mvnw.cmd -B -ntp clean verify` passed in 5m27s: all 94 tests (74 Surefire,
  20 Failsafe including five E2E), zero failures/errors/skips. Both source-built
  images and the Compose acceptance exercise passed with non-root execution,
  accepted/duplicate/rejected outcomes, Kafka notification and down/up retention
  followed by new consumption; isolated containers/network/volumes were cleaned up.
  Trivy 0.74.0 completed both image scans and SBOM generation successfully:

  | Image | HIGH | CRITICAL | Secrets | MEDIUM | LOW | SBOM components |
  | --- | --- | --- | --- | --- | --- | --- |
  | transaction-api | 0 | 0 | 0 | 43 | 4 | 183 |
  | transaction-processor | 0 | 0 | 0 | 33 | 4 | 194 |

  The checkout secret scan also passed. Remaining MEDIUM/LOW findings are recorded,
  not suppressed; the existing policy does not block them. No exception was added.
  Reports/SBOMs are in `artifacts/remediation/<module>/`; source secret findings are
  in `artifacts/remediation/source/secrets.json`. Local image tags are
  `payments/<module>:m2-remediation`. Their revision labels identify the base commit
  2f50ec3, not a committed remediation: this validation includes the uncommitted POM
  change. Local source patch and image/SBOM hashes are retained in that artifacts
  directory. CI must rebuild/validate the eventual committed revision before delivery.
  Logs: `%TEMP%/payments-m2-remediation-verify.log`,
  `%TEMP%/payments-m2-remediation-compose.log` and the module build/scan logs with
  the same prefix. Local dependency remediation criteria are met; remote CodeQL/CI,
  GHCR permissions and registry digests remain unverified. No push or publication
  was performed during remediation. M2 is not yet complete.

- 2026-09-21 (M2, local worktree based on 722df65): AGENTS.md, ROADMAP.md and this
  state agree with the module boundaries, acknowledgement ordering and M1 container
  implementation. M1 is now committed; the earlier M1 entry below is historical.
  Corrected Docker context rules so allowed parent directories cannot reinclude
  host targets/tests/private files. Both application images rebuilt successfully.
  `.\mvnw.cmd clean verify` passed in 4m35s: 94 tests (74 unit/contract/MVC and 20 IT,
  including five E2E), zero failures/errors/skips. Log: `%TEMP%/payments-m2-clean-verify.log`.
  The Compose acceptance script using the prebuilt, revision-labelled images passed
  non-root execution, HTTP acceptance, identical retry, durable rejection, Kafka
  notification and down/up data retention/new consumption. Cleanup completed for
  its isolated project. Log: `%TEMP%/payments-m2-compose.log`.
  Nine Python policy/promotion tests passed, including a tampered archive preventing
  every Docker operation. Pinned actionlint 1.7.7 reported no workflow diagnostics.
  The checkout secret scan passed; its offline mode avoids irrelevant Maven POM
  resolution (the initial online attempt hit Maven Central HTTP 429).
  These are local results, not a GitHub CI run for the new M2 workflow.
  Trivy 0.74.0 completed both image scans using its downloaded vulnerability/Java
  databases: API has 22 HIGH + 8 CRITICAL findings; processor has 20 HIGH + 2 CRITICAL.
  These are per-image advisory occurrences, not 52 distinct vulnerabilities or
  demonstrated exploits. All blocking findings are in Java dependencies; no image
  secret was detected. Both scan commands correctly failed the policy (exit 1).
  Affected dependencies include Boot 4.0.1, Spring 7.0.2, Kafka clients 4.1.1,
  Micrometer 1.16.1, LZ4 1.8.0 and Jackson; Tomcat 11.0.15 is API-specific, while
  PostgreSQL JDBC/Spring Data and Jackson 2 also appear in the processor report.
  No dependency upgrade or security exception was silently introduced.
  Sanitized reports: `artifacts/images/<module>/trivy.json`; CycloneDX inventories:
  `artifacts/images/<module>/sbom.cdx.json` (181 API / 194 processor components).
  These generated local artifacts are ignored by Git; CI uploads the equivalent
  reports/SBOMs even on policy failure but withholds the verified image bundle.
  Delivery remains blocked. CodeQL execution on GitHub, successful bundle promotion
  and GHCR package permissions/digests have not been verified. No push,
  registry authentication or publication was performed.

- 2026-09-19 (M1): Checked AGENTS.md/ROADMAP.md against module POMs, processor ordering
  and Compose. Corrected JUnit wording to Boot-managed Jupiter 6 and documented the
  E2E module. Implemented Dockerfiles, a source-only Docker context, application
  Compose services and scripts/verify-compose.ps1; business code is unchanged.
  Built from a temporary export of tracked/new source files with no .git, .env,
  target directories or prebuilt JARs. `docker compose -p payments-m1-validation build`
  succeeded without a host Maven build. The acceptance script then rebuilt and ran
  in its own disposable project with random loopback ports and exited 0:
  `PASS: non-root, HTTP acceptance, idempotent retry, durable rejection, Kafka notification, volumes and restart.`
  It verified UID 10001 for both apps, no database environment in the API, one immutable
  ledger row after identical HTTP retry, negative rejection only in the audit, and a
  versioned rejection notification with transactionId key/correlation/reasons.
  After down/up with named volumes retained, ledger/audit rows survived and a new
  payment was processed. Finally removed only its temporary containers/network/volumes.
  The first run exposed a missing curl executable in the JRE image; the API Dockerfile
  now installs it and the full exercise passed on rerun. `docker compose --env-file
  .env.example config --quiet` also passed. Local evidence logs are
  `%TEMP%/payments-m1-build.log` and `%TEMP%/payments-m1-smoke.log`.
  `.\mvnw.cmd clean verify` then passed in 4m35s: 74 Surefire tests and 20 Failsafe
  tests (including five real-JAR E2E scenarios), with no failures, errors or skips.
  Its log is `%TEMP%/payments-m1-clean-verify.log`. Packaging and runtime use the same
  application sources; no business logic or acknowledgement policy was changed.
  No CI run or image publication is claimed for these uncommitted M1 changes.

- 2026-09-19: Inspected failed GitHub Actions job
  [105863836497](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35428956254/job/105863836497)
  for commit e19d343. The Kafka native container crashes during setup with a
  SegfaultHandler report in GraalVM's getpwuid/user-property initialization. Both API
  integration contexts fail to start; processor and E2E modules are skipped.
  Replaced apache/kafka-native:4.1.1 with the supported JVM image apache/kafka:4.1.1
  in API, processor and E2E fixtures, matching Compose. Kafka version and test
  assertions remain unchanged. No local build/tests were run, as requested.
  GitHub Actions subsequently passed for commit `2937501`
  ([run 35462303146](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35462303146));
  the workflow runs the full clean verify lifecycle without disabling tests or increasing timeouts.

- 2026-09-19: Rejection notification focused validation passed with
  `.\mvnw.cmd -pl transaction-processor -am verify`: 38 unit tests and 13 integration
  tests, no failures, errors or skips. Tests cover version-1 JSON round-trip without
  Java headers, store-before-send ordering, no send on COMMIT failure, configured
  output topic/key, and publication failure followed by manual restart/replay.
  `docker compose --env-file .env.example config --quiet` passed. An isolated Compose
  project created both topics and successfully reran kafka-init; its containers,
  network and volume were removed afterwards.
  The subsequent `.\mvnw.cmd clean verify` passed in 3m03s: 74 Surefire tests and
  20 Failsafe tests (including all five real-JAR E2E scenarios), with no failures,
  errors or skips. API code, migrations and CI configuration are unchanged.
  These are local results; no GitHub run is claimed for this uncommitted change.

- 2026-09-19: Durable business rejection persistence passed the focused processor
  command `.\mvnw.cmd -pl transaction-processor -am verify`, then the full
  `.\mvnw.cmd clean verify` in 3m23s: 68 Surefire tests and 19 Failsafe tests
  (including five E2E), with no failures, errors or skips. PostgreSQL tests verify
  V1-to-V2 upgrade preserving ledger data, audit indexes, exact decimal/null values,
  metadata-independent deduplication and immutable first reasons. Kafka tests prove
  a rejection COMMIT failure rolls back without advancing the offset; manual restart
  replays successfully and repeated rejection deliveries retain one audit row.
  E2E scenarios additionally assert ledger/audit separation and durable conflicts.
  API, shared contracts, V1 and CI configuration are unchanged. These results are
  local validation; the earlier linked GitHub run covers its stated commit only.

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
- The processor context test applies Flyway V1 and V2 automatically on startup.
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
[ADR 0003](adr/0003-business-rejections.md), [ADR 0004](adr/0004-payload-conflicts.md),
and [ADR 0005](adr/0005-durable-business-rejections.md), extended by
[ADR 0006](adr/0006-rejection-notifications.md). Local application containers follow
[ADR 0007](adr/0007-containerized-local-platform.md).

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
  rejection audit persistence must commit before normal return permits acknowledgement.
- Kafka auto-commit is disabled; RECORD acknowledgement follows committed database
  work or verified duplication for accepted events. Business rejections are persisted
  idempotently in transaction_rejections and then published with broker confirmation
  before logging/ack, without ledger writes. Duplicate audits still trigger publication
  to recover a failed send; notifications reflect the current rejected attempt.
  Technical/contract failures stop
  the listener without skipping records.
  Recovery requires correcting the failure and restarting; no retry/DLQ topics exist.
  This is at-least-once delivery with idempotent database effects.
- Amount sign and supported-currency checks belong to the processor. An intake 202
  confirms publication, not business validation or accounting completion.
- Normal startup requires externally provisioned input/output topics. Compose's
  one-shot kafka-init provisions both topics idempotently. The test launcher and
  API integration configuration create a disposable three-partition topic; processor
  integration configuration uses one partition. Both use one replica.

## Current Gaps and Known Build Notes

- No outbox: committed rejection audits and Kafka notifications are not atomic.
  Recovery depends on replay of retained input; publication can be repeated and the
  minimal notification contract does not identify each distinct rejected payload.
- Define audit retention/access controls and operational recovery for malformed
  events; currently technical/contract failures stop consumption. Add listener
  health monitoring: the application process can remain alive after the listener stops.
- Add concurrent duplicate tests and process-crash testing between database and
  offset commits. The end-to-end test simulates server unavailability by stopping
  PostgreSQL, not a network partition; processor tests separately inject commit failure.
- Add authentication, status lookup,
  distributed traces, business metrics, and operational dashboards. The event field
  supplies correlation metadata; it does not itself implement distributed tracing.
- Spring Boot manages JUnit Jupiter 6.0.3, required by Spring Framework 7. AGENTS.md
  now reflects the actual version and includes the payment-e2e-tests module.
- Failsafe is active in `integration-test` / `verify` for `*IT` tests. All container
  tests were renamed; Surefire retains unit/MVC/contract tests. CI already invokes
  `clean verify` and collects both report directories on failure.
- Maven/Jansi/Guava and Mockito emit Java 25 native-access, deprecated-Unsafe, and
  dynamic-agent warnings. These did not fail the focused tests.

## Next Objective

Validate M2 secure delivery on GitHub for the committed dependency remediation,
following [the approved AI and DevSecOps roadmap](ROADMAP.md). Local tests,
Compose acceptance, image/secret scans and SBOM generation now pass. After approval
to push, verify the full CI workflow on that exact revision; after separate explicit
publication approval, verify promotion of both tested archives to GHCR.

The M2 pipeline is committed as 2f50ec3; dependency remediation is locally validated.
Remote CodeQL/CI execution and GHCR publication remain unverified.
M1 is committed as 722df65; the prior green CI remains specific to commit 2937501.
The outbox stays deferred per the approved roadmap. No push/publication is authorized
by this state update; retain the user's confirmation requirement.

## Acceptance Criteria for the Next Objective

- All GitHub validation jobs pass for the exact approved commit, including the
  94 current tests, Compose acceptance, CodeQL and secret/image policies.
- Both images retain successful scan reports, SBOMs and the committed source SHA.
- Normal push/PR runs perform no registry publication. Only the explicitly approved
  manual dispatch promotes the same verified archives and records both GHCR digests.
- Link the successful run and digest evidence here before declaring M2 complete;
  do not proceed to M3 while remote delivery remains unverified.

