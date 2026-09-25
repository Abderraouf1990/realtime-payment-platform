# Project State

Updated: 2026-09-25

## Objectif principal et statuts actuels

Développer une expertise pratique en ingénierie IA et démontrer des compétences
DevSecOps senior avec une plateforme de paiement déployable et un assistant
d'incident sécurisé et évalué. Les décisions de paiement restent déterministes.
L'ordre approuvé des jalons reste celui de [ROADMAP.md](ROADMAP.md).

| Statut | Situation au 2026-09-25 |
| --- | --- |
| TERMINÉ | M0 à M2 selon les [preuves datées](PASSATION.md#validated-state) ; M3 validé localement et commité dans `6c84939`. Cela ne constitue pas une validation CI de M3. |
| EN COURS | M4 : santé, logs, compteurs et traces commités (`6130a81`) ; progression/lag validés localement sur le travail non commité basé sur cette révision (117 tests). Les autres travaux d'observabilité restent ouverts. |
| À FAIRE | M4 : collecte, alertes, dashboards et autres incidents ; M5 : assistant d'incident. Les critères de la prochaine tâche restent en fin de fichier. |
| BLOQUÉ | Aucun blocage actuel démontré pour poursuivre M4. Une nouvelle publication, un push ou un déploiement cloud restent soumis à autorisation ; ce ne sont pas des prérequis au travail local. |
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
  API Actuator health is checked. New processor builds expose HTTP health on
  container loopback, without a Compose port/healthcheck; published M2 images remain
  non-web. A running processor container alone is not a guarantee of listener health.
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
  API/infra probes check their actual availability. The default published M2 processor
  still lacks HTTP health; optional processor probes require a new M4 image.
  See ADR 0009 for the M3 configuration rollback scope and ADR 0010 for M4 health.
- M4 source builds add Actuator listener health on loopback 8081 by default
  (PROCESSOR_ADDRESS/PROCESSOR_PORT). STARTING, RUNNING, STOPPED, MISSING and PAUSED
  reflect local container lifecycle only; details contain no payload or exception.
  Readiness includes the listener; startup/liveness remain independent of dependency
  failures. Manual restart and database/Kafka acknowledgement ordering are unchanged.
  Seven unit tests and the real-JAR PostgreSQL outage E2E cover these distinctions.
- Processor processing logs now use Boot Logstash JSON with validated IDs,
  outcome/reasonCodes and Kafka partition/offset; no account, raw payload or exception
  is attached to these processing records. This does not claim global log redaction.
  `payments.processing.attempts` has four fixed `outcome` series (accepted, duplicate,
  rejected, technical_failure), with no ID/reason labels. Read-only Actuator metrics
  are exposed alongside health on the same loopback management surface.
  These are best-effort per-JVM listener attempts before ack, reset on restart,
  not unique payments or audit totals. Pre-listener deserialization/poll failures
  and offset-commit failures are outside the counter. Recording failures cannot
  turn processing success into failure or mask its original sanitized error.

- Source builds now use Boot-managed Micrometer/Brave W3C propagation across HTTP,
  Kafka intake, processor delivery and rejection publication. Scoped traceId/spanId
  enrich processor logs independently of business IDs. Export is opt-in, sampling
  defaults to 10%; no new collector deployment or business/schema change is added.
  See [ADR 0012](adr/0012-http-kafka-tracing.md) and the
  [trace exercise](runbooks/http-kafka-tracing.md).

- Source-built processors now poll the configured topic/group's committed and
  earliest/end offsets with a separate read-only Admin client. Four unlabelled
  `payments.consumer.*` gauges expose lag, observation availability/age and
  observed committed-progress age. HTTP reads are cached; failures produce
  unavailable lag (-1), and listener stop does not stop sampling. The baseline
  is group-wide, not local fetch position or unique payments. Default cadence
  is 5 seconds with a 2-second query wait budget; restart forgets historical
  progress but reads durable offsets. See [ADR 0013](adr/0013-consumer-progress-and-lag.md)
  and the [lag exercise](runbooks/consumer-progress-and-lag.md).

## Validated State

- M4, progression/lag (2026-09-25, travail non commité basé sur `6130a81`) :
  `.\mvnw.cmd -B -ntp clean verify` réussit en 5:29, terminé à 11:36:12 +02:00,
  code de sortie 0. Recompte XML : 117 tests (94 Surefire et 23 Failsafe,
  dont sept E2E), aucun échec, erreur ou ignoré. Les sept nouveaux tests unitaires
  couvrent états inconnus/idle, agrégation, progression, rétention, erreurs et resets.
  KafkaProgressSourceIT observe deux partitions avant toute assignation sans commit.
  Les vrais JARs démontrent lag 1 → 2 pendant l'arrêt du listener, puis 0 après
  reprise manuelle, et lag -1/disponibilité 0 quand Kafka est indisponible.
  Santé, traces, compteurs, persistance et absence d'ack prématuré restent vérifiés.
  Journal : `artifacts/m4-progress-verify.log` ; rapports sous `target/*-reports`.
  Aucun blocage restant démontré. Aucun commit, push, run CI, scan/publication
  d'image ou déploiement M4 pour ce volet ; les preuves CI de M2 restent propres
  à `cca00ee`. Exercice et limites : [runbook](runbooks/consumer-progress-and-lag.md),
  [ADR 0013](adr/0013-consumer-progress-and-lag.md), détails dans [PASSATION.md](PASSATION.md).
- M4, traces (2026-09-25, travail local non commité basé sur `71b94ff`) :
  `.\mvnw.cmd -B -ntp clean verify` affiche BUILD SUCCESS en 5:07, terminé à
  11:11:24 +02:00. Rapports XML : 108 tests (87 Surefire, 21 Failsafe dont six
  E2E), zéro échec, erreur ou ignoré. La chaîne parent/enfant HTTP → Kafka →
  processor → rejet est vérifiée sur les spans exportés des vrais JARs.
  Messages sans contexte/invalide et retry HTTP restent isolés ; le replay
  conserve la trace avec un nouveau span. Une réponse 503 de l'exporteur
  n'empêche ni l'arrêt sans ack sur panne SQL ni le replay/ack après reprise
  manuelle. Santé, logs, compteurs et invariants métier restent couverts.
  Journal : `artifacts/m4-tracing-verify.log` ; spans/logs E2E dans
  `payment-e2e-tests/target/failsafe-reports/`. Aucun blocage restant démontré.
  Aucun commit, push, nouvelle CI, scan/publication d'image ou déploiement M4
  effectué. Les preuves CI de `cca00ee` ne couvrent pas ces changements.
  Détails, correction du test asynchrone et exercice : [PASSATION.md](PASSATION.md)
  et [runbook traces](runbooks/http-kafka-tracing.md).
  Ce volet a ensuite été commité dans `6130a81`, sans push, à la demande de
  l'utilisateur. Les mentions « non commité » ci-dessus datent de sa validation.
- M4, logs/métriques (2026-09-22, travail local basé sur `10a6808`) : les cinq
  E2E ciblés passent, puis `.\mvnw.cmd -B -ntp clean verify` réussit en 4:16 :
  107 tests (87 Surefire, 20 Failsafe dont cinq E2E), aucun échec, erreur ou ignoré.
  Six nouveaux tests unitaires couvrent les outcomes, IDs/labels et une panne de
  compteur ; les E2E lisent les vrais logs JSON et compteurs HTTP, avec les
  assertions ledger/offset/santé conservées et le reset après redémarrage vérifié.
  Aucun commit, push, run CI ou publication d'image pour ce volet dans cette session.
  Ce volet est désormais commité dans `71b94ff`. ADR 0011 et le runbook détaillent
  les limites ; preuves/commandes dans PASSATION.md.
- M4, premier volet (2026-09-22, travail local basé sur `7b31f6d`) : sept nouveaux
  tests unitaires et l'E2E PostgreSQL ciblé passent. Le cycle complet
  `.\mvnw.cmd -B -ntp clean verify` passe en 4:05 : 101 tests (81 Surefire,
  20 Failsafe dont cinq E2E), aucun échec, erreur ou test ignoré.
  La panne laisse listener/readiness à 503, liveness à 200 et l'offset inchangé ;
  après restauration PostgreSQL puis redémarrage manuel, santé et replay réussissent.
  Helm lint passe avec/sans sondes processor et le rendu opt-in a été inspecté.
  Aucune validation Kubernetes M4 en exécution, CI M4 ou nouvelle publication
  n'est revendiquée. Voir le [runbook](runbooks/processor-postgresql-outage.md)
  et les détails dans PASSATION.md. Ce volet a ensuite été commité dans `10a6808` ;
  aucun push effectué par l'assistant.
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
- Aucun push effectué par l'assistant dans cette session. État distant et éventuels
  runs CI pour `6c84939` / `7b31f6d` / `10a6808` : **À confirmer**. La réorganisation documentaire
  précédente n'avait pas relancé de tests ; le volet M4 ci-dessus les a exécutés.
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
Source-built processor health follows [ADR 0010](adr/0010-processor-listener-health.md).
Processing logs/counters follow [ADR 0011](adr/0011-processing-logs-and-attempt-metrics.md).
HTTP/Kafka tracing follows [ADR 0012](adr/0012-http-kafka-tracing.md).
Group progress/lag follows [ADR 0013](adr/0013-consumer-progress-and-lag.md).

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
  events; currently technical/contract failures stop consumption. Listener health
  and group progress/lag monitoring are available in new builds; alerting remains absent.
  RUNNING does not prove broker/database reachability, assigned partitions or progress.
  Idle PostgreSQL outages are detected only after a failed processing attempt.
- M4 Helm probes are opt-in, disabled for the default M2 images; runtime deployment
  with a new M4 image remains to be validated. No M4 image publication or CI is claimed.
- Add concurrent duplicate tests and process-crash testing between database and
  offset commits. The end-to-end test simulates server unavailability by stopping
  PostgreSQL, not a network partition; processor tests separately inject commit failure.
- Add authentication, status lookup,
  collection and operational dashboards. Traces and outcome
  counters are best-effort attempt telemetry, not durable audit. Business correlation
  remains independent of the W3C context carried in transport headers.
- Spring Boot manages JUnit Jupiter 6.0.3, required by Spring Framework 7. AGENTS.md
  now reflects the actual version and includes the payment-e2e-tests module.
- Failsafe is active in `integration-test` / `verify` for `*IT` tests. All container
  tests were renamed; Surefire retains unit/MVC/contract tests. CI already invokes
  `clean verify` and collects both report directories on failure.
- Maven/Jansi/Guava and Mockito emit Java 25 native-access, deprecated-Unsafe, and
  dynamic-agent warnings. These did not fail the focused tests.

## Next Objective

Continue M4: add reproducible local metrics collection with a minimal dashboard
and a tested backlog/observation-unavailable alert, using the existing listener,
attempt and committed-lag signals. Keep this bounded to the incident demonstration
before M5; no new payment feature, cloud provisioning or image publication.

## Acceptance Criteria for the Next Objective

- Pin collector/dashboard images and keep access local; expose only the metrics
  necessary for the exercise, without payloads, credentials or business-ID labels.
- Show healthy idle consumption, stopped-listener backlog, manual drain and lost
  observations, accounting for sampling freshness and JVM counter resets.
- Demonstrate an alert firing and resolving without restarting the processor or
  changing payment processing/acknowledgement behavior.
- Provide reproducible startup, queries and cleanup; run the full Maven lifecycle
  and the local collection exercise. Keep local evidence separate from CI.
- M4 remains in progress; finish its bounded incident demonstration before M5.
- Earlier objectives and criteria are preserved in [PASSATION.md](PASSATION.md).
