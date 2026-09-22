# Project State

Updated: 2026-09-22

## Objectif principal et statuts actuels

Développer une expertise pratique en ingénierie IA et démontrer des compétences
DevSecOps senior avec une plateforme de paiement déployable et un assistant
d'incident sécurisé et évalué. Les décisions de paiement restent déterministes.
L'ordre approuvé des jalons reste celui de [ROADMAP.md](ROADMAP.md).

| Statut | Situation vérifiée au 2026-09-22 |
| --- | --- |
| TERMINÉ | M0 à M2 selon les [preuves datées](PASSATION.md#validated-state) ; M3 validé localement et commité dans `6c84939`. Cela ne constitue pas une validation CI de M3. |
| EN COURS | Aucun développement applicatif en cours observé dans le dépôt ; M4 n'est pas encore implémenté. |
| À FAIRE | M4 : santé du listener Kafka et exercice PostgreSQL avec reprise manuelle, puis les autres travaux d'observabilité ; M5 : assistant d'incident. Les critères détaillés restent en fin de fichier. |
| BLOQUÉ | Aucun blocage actuel démontré pour commencer M4. Une nouvelle publication, un push ou un déploiement cloud restent soumis à autorisation ; ce ne sont pas des prérequis au travail local. |
| ABANDONNÉ | Aucun nouvel abandon observé. L'outbox et M6 restent différés, pas abandonnés. |

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
- M3 adds `deploy/helm/payments`, a digest-pinned kind configuration and an isolated
  Windows acceptance script. The chart reuses M2's immutable application images,
  provisions both topics, mounts separate infrastructure PVCs and references an
  externally supplied database Secret. Resource budgets, numeric non-root identities,
  seccomp and dropped capabilities apply to all workloads. Application root
  filesystems are read-only; API database isolation is preserved.
  API/infra probes check their actual availability; the processor still lacks a
  listener-health endpoint and requires manual restart after technical failure.
  See ADR 0009 for this explicit limitation and the controlled configuration rollback.

## Validated State

- M3 est commité dans `6c84939`. La validation locale du 2026-09-22 a passé :
  `.\mvnw.cmd -B -ntp clean verify` en 4:03, 94 tests (74 Surefire et 20 Failsafe,
  dont cinq E2E), aucun échec, erreur ou test ignoré.
- `scripts/verify-kubernetes.ps1` a validé les images M2 par digest, les permissions,
  paiements/doublons/conflits/rejets, l'upgrade/rollback de configuration Helm,
  les PVC après recréation des pods et la reprise manuelle ; cluster isolé nettoyé.
  Cela ne prouve ni un rollback SQL, ni une reprise automatique, ni la haute disponibilité.
- M2 reste validé et publié pour `cca00ee` par le
  [run 35661616528](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35661616528).
  Les digests et preuves de publication restent dans [le relevé M2](evidence/m2-publication.json).
- Aucun push effectué par l'assistant dans cette session. État distant et éventuel
  run CI pour `6c84939` : **À confirmer**. Aucun test relancé pour cette réorganisation.
- Historique complet des validations, incidents résolus, commandes et décisions
  remplacées : [PASSATION.md](PASSATION.md). Les anciens statuts y sont conservés.

## Architecture Decisions

See [ADR 0001](adr/0001-transaction-intake-contract.md) and
[ADR 0002](adr/0002-ledger-consumption.md) and
[ADR 0003](adr/0003-business-rejections.md), [ADR 0004](adr/0004-payload-conflicts.md),
and [ADR 0005](adr/0005-durable-business-rejections.md), extended by
[ADR 0006](adr/0006-rejection-notifications.md). Local application containers follow
[ADR 0007](adr/0007-containerized-local-platform.md), secure delivery follows
[ADR 0008](adr/0008-secure-image-delivery.md), and local Kubernetes follows
[ADR 0009](adr/0009-local-kubernetes.md).

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

Start M4 in [the approved roadmap](ROADMAP.md) with one bounded task: expose and
test processor Kafka-listener health, then document a reproducible PostgreSQL
outage/manual-recovery incident using that signal. A stopped listener must no
longer be confused with a healthy running JVM. Preserve the existing acknowledgement
and manual recovery semantics; do not silently add automatic retries or an outbox.

M3 deployment acceptance passed locally; M2 remains verified for cca00ee. Subsequent
M4 work will add the remaining metrics, traces, dashboards and incident exercises
before M5. Existing M2 authorization does not authorize new Git pushes, future
image publications or cloud provisioning.

## Acceptance Criteria for the Next Objective

- A processor health signal distinguishes startup, a running listener and a listener
  stopped by technical failure, without exposing credentials or payment payloads.
- Unit/integration tests demonstrate the signal transition during a PostgreSQL
  outage while retaining the no-premature-ack guarantee.
- Restore PostgreSQL and restart the processor manually; verify replay/persistence
  and the health signal's recovery. Document the commands in a runbook.
- Explain readiness versus liveness and configure probes without creating an
  automatic restart loop that changes the documented recovery strategy.
- Run the full Maven lifecycle and record local results separately from CI; do not
  publish new images or claim the old M2 digests contain the new observability code.
