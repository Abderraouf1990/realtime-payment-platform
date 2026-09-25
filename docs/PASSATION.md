# Passation — historique et preuves

Updated: 2026-09-25

## Rôle de ce document

Ce fichier conserve l'historique détaillé transféré depuis PROJECT_STATE.md
avec l'autorisation de l'utilisateur le 2026-09-22. Les deux blocs ci-dessous
(point de contrôle/décisions remplacées et validations) sont repris intégralement.
Aucune information historique n'a été supprimée lors de ce transfert.

Lire [PROJECT_STATE.md](PROJECT_STATE.md) pour l'état actuel, les limites,
la prochaine tâche et ses critères ; [ROADMAP.md](ROADMAP.md) reste la référence
pour les priorités, et les [ADR](adr/) pour les décisions d'architecture.
Les anciens statuts, commandes et résultats ci-dessous décrivent leur date
ou leur commit, pas nécessairement HEAD. Les chemins de journaux locaux et
d'artefacts expirables sont des références historiques, sans garantie de disponibilité.

Pour les prochaines passations, ajouter ici les diagnostics, essais, corrections
et preuves datées ; ne garder dans PROJECT_STATE.md que leur résultat actuel
et les problèmes encore ouverts.

## Point de contrôle après commit — 2026-09-22

- `git status --short` était vide avant cette mise à jour. HEAD est `6c84939`
  (`feat(deploy): add validated local Kubernetes deployment with Helm`), après
  `3a1a310`, `cca00ee`, `2f50ec3` et `722df65`.
- Le commit M3 contient 13 fichiers : chart Helm, configuration kind, deux scripts,
  ADR 0009, README, ROADMAP et ce fichier. Aucun code métier n'y est modifié.
- Les résultats locaux déjà consignés ci-dessous ont été relus : journal Maven
  `BUILD SUCCESS` en 4:03, rapports XML totalisant 74 tests Surefire et 20 Failsafe,
  sans échec, erreur ni test ignoré ; journal Kubernetes `PASS`, nettoyage terminé
  et historique Helm avec rollback à la révision 1 enregistré comme révision 3.
  Aucun test n'a été relancé pour cette seule mise à jour documentaire.
- Aucun push n'a été effectué par l'assistant dans cette session. L'état distant
  et un éventuel run GitHub Actions pour `6c84939` sont **À confirmer** ; les
  preuves CI de `cca00ee` ne sont pas étendues à ce nouveau commit.

## Historique / Décisions remplacées

Les entrées datées de « Validated State » sont conservées intégralement comme
preuves de leur étape ; leurs statuts intermédiaires ne décrivent pas tous HEAD.
Les précisions suivantes n'ajoutent pas de nouvelle décision d'architecture :

- « Delivery remains blocked » et « M2 awaits publication approval » ont été
  dépassés par la remédiation puis la publication autorisée du 2026-09-22,
  documentées ci-dessous ; M2 reste terminé pour `cca00ee`.
- « No documentation commit » et « M3 is the next milestone » dans l'entrée M2
  décrivent son état avant `3a1a310`, puis avant M3. M3 est désormais commité dans
  `6c84939` ; la prochaine tâche reste M4, sans changement de ses critères.
- L'ancienne mention de conflits uniquement journalisés (« log-only ») décrit
  le jalon du 2026-09-18. La persistance durable des rejets du 2026-09-19 et
  Flyway V2 l'ont remplacée, comme indiqué dans l'implémentation actuelle.

## Validated State

- 2026-09-22 (M3, local working tree based on 3a1a310):
  `scripts/verify-kubernetes.ps1` passed on the isolated cluster
  `payments-m3-283e8ef3` and removed it on exit. Toolchain: kind 0.33.0
  (download SHA-256 checked), Kubernetes 1.34.11, kubectl 1.34.1, Helm 3.15.2,
  Docker Desktop Linux containers with approximately 8 GB RAM.
  Helm lint and server-side dry run passed; actual pods ran under enforced
  Pod Security restricted/v1.34. Application digest references matched the M2
  publication evidence; runtime UID was 10001, application roots were read-only,
  privileges/capabilities/token mounting were disabled and resource budgets present.
  The API received no database configuration.
  HTTP acceptance, identical retry, unchanged ledger on conflict, negative amount
  and non-EUR rejection passed. Helm revision 2 changed the pod-template marker;
  revision 3 rolled back to revision 1. Payments succeeded after both operations.
  Recreating PostgreSQL/Kafka pods retained three ledger rows and three audits;
  manual processor restart allowed a fourth valid transaction. Three retained
  rejection notifications had the expected transaction keys, correlation IDs and
  schema version. Raw pod/Helm history evidence remains under ignored
  `artifacts/payments-m3-283e8ef3/`; no kubeconfig or Secret is committed.
  The user's existing `minikube` context was preserved. An initial attempt exposed
  pg_isready's OS-user lookup for UID 10001; explicitly supplying PGUSER/PGDATABASE
  from the Secret fixed initialization, and the full fresh-cluster rerun passed.
  This proves local configuration rollback/PVC retention, not schema rollback,
  automatic listener recovery, high availability or cloud readiness. No M3 CI run,
  Git push or new image publication was performed.
  `.\mvnw.cmd -B -ntp clean verify` also passed in 4:03: 94 tests (74 Surefire,
  20 Failsafe including five PaymentFlowIT scenarios), zero failures/errors/skips.

- 2026-09-22 (M2 completed): following explicit user authorization to publish both
  images, dispatched CI on the already-pushed `codex/build-mvp` revision
  `cca00eee6ed088fe394afb31ce72d97d408094fa`, with `publish=true` and that exact
  `approved_sha`. [Manual run 35661616528](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35661616528)
  succeeded for all five jobs, including `publish`. The run repeated all 94 Maven
  tests (zero failures/errors/skips), nine Python tests, CodeQL, secret checks,
  image builds, Compose acceptance, both image gates and SBOM generation.
  The publication job loaded its own verified archives and pushed both images
  without rebuilding. No Git push or new source commit was needed.

  | Image repository (under ghcr.io/abderraouf1990/) | Verified registry digest |
  | --- | --- |
  | realtime-payment-platform-transaction-api | sha256:07a331642be5ea3f0c75d7c60de355fd36439814fc2d36837cffe192e9279992 |
  | realtime-payment-platform-transaction-processor | sha256:7999d5611fd1452ceeef66dd64c8fb381b281a2cf854596993d4b92404e3d8db |

  Both use tag `sha-cca00eee6ed088fe394afb31ce72d97d408094fa`.
  Independently downloaded the run's `verified-images` and `published-images`
  artifacts and checked their archive digests against GitHub's metadata. Checked
  each image archive and SBOM hash against the bundle manifest. Read both GHCR
  tag manifests and config blobs: registry digests match the publication record;
  config digests match the tested image IDs; revision/source labels match the
  approved commit/repository; both run as UID/GID `10001:10001`.
  [Recorded evidence](evidence/m2-publication.json) retains the run URL, complete
  tags, registry/config digests and artifact IDs/digests. The verified bundle is
  artifact 10667679618, retained until 2026-09-28T22:19:33Z; the publication record
  is artifact 10667835254. Local verification files are in `artifacts/m2-publication/`.
  This demonstrates M2 delivery for this revision, not signed provenance, atomic
  publication of the pair, absence of all vulnerabilities or production readiness.
  No application code or workflow change was required. Documentation is updated;
  no documentation commit or Git push was performed. M3 is the next milestone.

- 2026-09-22 (M2 remote validation): checked the clean local checkout and remote
  `codex/build-mvp`; both point to `cca00eee6ed088fe394afb31ce72d97d408094fa`.
  AGENTS.md, the module POMs and implementation agree on Boot 4.0.8/Tomcat 11.0.26,
  module separation and commit-before-publication/ack ordering. The remaining
  documentation drift was the now-obsolete statement that remote CI was unverified.
  [CI run 35653382625](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35653382625)
  completed successfully for that exact commit on a push (2026-09-21 UTC).
  Inspected job/step results and authenticated GitHub job logs:
  - `build` (106510934547): `./mvnw clean verify` succeeded; 94 tests, zero
    failures/errors/skips, including five E2E. Nine Python gate tests and actionlint passed.
  - `secrets` (106510934126): checkout scan and redacted report upload succeeded.
  - `code-analysis` (106510934403): CodeQL extraction, analysis, blocking gate and
    SARIF artifact upload succeeded.
  - `images` (106512165801): both builds, Compose acceptance including restart/data
    retention, both security gates, SBOM generation and commit-bound bundle creation
    succeeded. No security threshold was relaxed.
  - `publish` (106513047160): skipped, as expected for a push; this run did not publish.

  GitHub lists four non-expired artifacts: `secret-scan`, `code-analysis`,
  `image-security` and `verified-images`. The latter is artifact 10663397461,
  with archive digest `sha256:481b185948507e7f1401171be65060f3e46f2c84fc4fb1fd5d8aa53c7ad7e5a5`,
  expiring 2026-09-28T20:54:45Z. This is an Actions artifact digest, not a GHCR image
  digest. Job logs and artifact metadata establish remote validation; registry
  publication, registry permissions and registry digests remain unverified.
  The default branch is `codex/build-mvp` and already contains the manual workflow.
  Prepared dispatch: that ref, `publish=true`,
  `approved_sha=cca00eee6ed088fe394afb31ce72d97d408094fa`. It will rerun all checks
  and publish only its own run's verified bundle, not reuse the previous push run's
  archives. If the ref advances, recheck the revision and approval before dispatch.
  No workflow was dispatched and no commit, push or publication was performed here.
  Only documentation changed; existing remote test evidence was checked rather
  than repeating the unchanged Java build locally. M2 awaits publication approval
  and registry verification; do not start M3.

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

## M4 — santé du listener et incident PostgreSQL — 2026-09-22

Le premier volet M4 ajoute la santé HTTP du listener aux nouvelles sources.
Les mentions historiques « processor non-web » des ADR 0007/0009 restent
vraies pour les images M2 publiées ; ADR 0010 décrit le nouveau comportement.
La politique de reprise manuelle et les acquittements restent inchangés.
L'objectif et les critères précédents sont conservés ci-dessous pour traçabilité.

Validation locale du travail basé sur `7b31f6d` :

- Le code, les POM et le chart confirmaient M0-M3 et l'absence de serveur HTTP
  dans le processor M2 ; la prochaine tâche correspondait bien au premier volet M4.
- Sept tests `KafkaListenerHealthIndicatorTests` passent (démarrage, absence,
  fonctionnement, arrêt normal/anormal, pause, reprise), sans détail sensible.
- L'E2E ciblé `PaymentFlowIT#databaseOutageLeavesOffsetUncommittedUntilManualRestartAndReplayIT`
  passe avec les vrais JARs, Kafka 4.1.1 et PostgreSQL 17.6. Il conserve les
  assertions de ledger/offset et ajoute listener/readiness 503, liveness 200,
  JVM encore active, maintien de DOWN après retour de PostgreSQL seul, puis
  retour UP et persistance unique après redémarrage manuel.
- `.\mvnw.cmd -B -ntp clean verify` passe en 4:05 le 2026-09-22 : 101 tests,
  81 Surefire et 20 Failsafe dont cinq E2E ; zéro échec, erreur ou test ignoré.
  Les autres E2E vérifient aussi le démarrage sain ; le cas valide vérifie que
  env/configprops/beans/metrics ne sont pas exposés sur le processor.
- `helm lint deploy/helm/payments` et le même lint avec
  `--set processor.healthProbes.enabled=true` passent. Le rendu avec une référence
  d'image locale M4 a été inspecté : port 8081, interface pod, startup/liveness sur
  liveness, readiness sur readiness. Cela ne vaut pas validation en cluster.
- Journaux locaux : `%TEMP%/payments-m4-unit.log`, `%TEMP%/payments-m4-outage.log`,
  `%TEMP%/payments-m4-verify.log`. Rapports XML et logs E2E sous les répertoires
  `target/*-reports` habituels. Aucun nouveau run GitHub ni scan d'image M4 n'a
  été exécuté. Les images M2 publiées et les services métier restent inchangés.
- Le runbook propose l'exercice automatisé exécuté et une procédure manuelle JAR.
  Cette procédure manuelle n'a pas été rejouée séparément dans cette session.
  Les sondes Helm sont désactivées par défaut pour les anciennes images ; leur
  validation avec une nouvelle image M4 reste à faire. Aucun commit, push ou
  publication n'a été effectué pour ce volet.
- Prochaine tâche M4 : logs de traitement structurés et métriques de résultats
  à cardinalité bornée. Traces, alertes/dashboards et autres incidents restent ouverts.

### Objectif de ce volet (conservé)

Start M4 in [the approved roadmap](ROADMAP.md) with one bounded task: expose and
test processor Kafka-listener health, then document a reproducible PostgreSQL
outage/manual-recovery incident using that signal. A stopped listener must no
longer be confused with a healthy running JVM. Preserve the existing acknowledgement
and manual recovery semantics; do not silently add automatic retries or an outbox.

M3 deployment acceptance passed locally; M2 remains verified for cca00ee. Subsequent
M4 work will add the remaining metrics, traces, dashboards and incident exercises
before M5. Existing M2 authorization does not authorize new Git pushes, future
image publications or cloud provisioning.

### Critères de ce volet (conservés)

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

## M4 — logs structurés et métriques de tentatives — 2026-09-22

État de départ propre à HEAD 10a6808 : le volet santé est bien commité ;
les mentions « aucun commit » ci-dessus décrivent les validations avant commit.
ADR 0011 étend l'exposition health-only du premier volet aux métriques en lecture
seule. L'historique des tests metrics-404 reste valable pour son ancienne révision.
Les critères du volet logs/métriques sont conservés ci-dessous.

### Objectif de ce volet (conservé)

Continue M4 in [the approved roadmap](ROADMAP.md): add structured processing logs
and bounded-cardinality outcome metrics (accepted, duplicate, rejected, technical
failure), keeping transaction/correlation IDs in logs rather than metric labels.
Preserve manual recovery, acknowledgement ordering and deterministic business rules.

M3 deployment acceptance passed locally; M2 remains verified for cca00ee. Subsequent
M4 work will add the remaining metrics, traces, dashboards and incident exercises
before M5. Existing M2 authorization does not authorize new Git pushes, future
image publications or cloud provisioning.

### Critères de ce volet (conservés)

- Structured processing logs retain transactionId/correlationId where validated,
  with explicit outcomes/reasons and no raw payload, account data or credentials.
- Metrics use bounded labels only; document whether each counts attempts or unique
  business transactions, especially for duplicate/replayed events.
- Tests exercise accepted, duplicate, rejected and technical-failure paths and
  prove observability does not change persistence, acknowledgement or recovery.
- Run the full Maven lifecycle, keep current health/outage coverage and update the
  runbook. Record local results separately from CI and image publication.
- The previous health/outage objective and its acceptance criteria are preserved
  in [PASSATION.md](PASSATION.md#m4--santé-du-listener-et-incident-postgresql--2026-09-22).

### Réalisation et compromis

- Instrumentation limitée à l'adaptateur Kafka : le service métier, les stores,
  la confirmation de publication et l'error handler restent inchangés.
- Logs JSON avec IDs validés et raisons bornées ; quatre séries Micrometer par
  outcome, sans identifiant en label. Actuator expose health et metrics en lecture.
- Compromis : des tentatives locales best effort, remises à zéro au redémarrage,
  permettent le diagnostic sans ajouter de transaction ou modifier l'ack.
  Elles ne remplacent pas l'audit PostgreSQL et ne comptent pas les erreurs
  avant invocation du listener ni celles du commit d'offset. Voir ADR 0011.
- Exercice reproductible : suivre les requêtes metrics et le filtre JSON du
  [runbook PostgreSQL](runbooks/processor-postgresql-outage.md), comparer les
  compteurs avant/après redémarrage avec les lignes ledger et offsets Kafka.

### Vérifications locales

- Un premier lancement ciblé a signalé une erreur de compilation du nouveau test :
  SimpleMeterRegistry n'est pas AutoCloseable. Le nettoyage utilise désormais
  try/finally ; les six nouveaux tests unitaires passent au lancement suivant.
- `.\mvnw.cmd -B -ntp -pl payment-e2e-tests -am "-Dit.test=PaymentFlowIT" "-Dfailsafe.failIfNoSpecifiedTests=false" verify` :
  BUILD SUCCESS en 2:28, les cinq E2E passent avec les vrais JARs, les logs JSON,
  les compteurs HTTP et les garanties ledger/ack/replay. Journal local :
  `%TEMP%\payments-m4-telemetry-e2e.log`.
- `.\mvnw.cmd -B -ntp clean verify` : BUILD SUCCESS en 4:16, terminé à
  22:17:23 +02:00 le 2026-09-22. Rapports XML : 107 tests, dont 87 Surefire et
  20 Failsafe (cinq E2E), zéro échec, erreur ou ignoré. Journal local :
  `%TEMP%\payments-m4-telemetry-verify.log`.
- Les E2E démontrent les quatre outcomes, les champs JSON, les seuls labels
  outcome du compteur, l'absence d'ack prématuré pendant la panne et le reset
  des compteurs après redémarrage/replay. La panne de compteur est testée en unitaire.
- Historique antérieur de ce fichier conservé intégralement ; les anciens critères
  de PROJECT_STATE sont déplacés ci-dessus. Son prochain objectif est maintenant
  la propagation du contexte de trace HTTP/Kafka. Aucun commit, push, run CI,
  déploiement Kubernetes M4 ou publication d'image pour ce volet dans cette session.

## M4 — traces HTTP/Kafka — 2026-09-25

État initial propre à `71b94ff` : les logs/métriques sont désormais commités.
Les anciens « aucun commit » décrivent leurs sessions historiques ; la CI de
ce commit n’a pas été vérifiée dans cette session. POM, séparation des modules,
contraintes SQL, ordre commit/publication/ack et images Helm correspondent aux
documents. AGENTS.md ne nécessite aucune correction.

### Objectif et critères conservés

## Next Objective

Continue M4 in [the approved roadmap](ROADMAP.md): propagate distributed trace
context from HTTP intake through Kafka to processor execution, with tests proving
the causal relationship and keeping business correlationId independent of trace IDs.
Preserve manual recovery, acknowledgement ordering and deterministic business rules.

M3 deployment acceptance passed locally; M2 remains verified for cca00ee. Subsequent
M4 work will add the remaining metrics, traces, dashboards and incident exercises
before M5. Existing M2 authorization does not authorize new Git pushes, future
image publications or cloud provisioning.

## Acceptance Criteria for the Next Objective

- HTTP producer and Kafka consumer spans retain a testable causal relationship;
  an event without trace context is still processed correctly.
- Keep correlationId and transactionId semantics unchanged; do not put payloads,
  account data, credentials or unbounded IDs in metric labels.
- Document retry/replay trace semantics and test context isolation between events;
  tracing/export failures must not acknowledge failed processing or change recovery.
- Run the full Maven lifecycle, retain health/log/counter/outage coverage, and
  document a reproducible local trace exercise without publishing new images.
- Earlier objectives and their criteria are preserved in [PASSATION.md](PASSATION.md).

### Réalisation, compromis et exercice

- Ajout du starter Zipkin géré par Boot 4.0.8 dans les deux applications et des
  options d'observation Kafka/W3C. Les services métier, contrats, migrations et
  politiques d'acquittement/reprise restent inchangés. Le JAR API ne contient
  toujours ni PostgreSQL, ni spring-jdbc, ni Hibernate core.
- Les logs processor portent traceId/spanId en plus des IDs métier ; aucun
  identifiant métier n'est ajouté aux tags de métriques ou de spans. L'export
  est opt-in et l'échantillonnage est de 10% par défaut. Les E2E utilisent 100%
  et un récepteur Zipkin HTTP local, sans infrastructure externe.
- Compromis : l'instrumentation native évite un protocole ou des wrappers métier
  spécifiques, mais les traces sont échantillonnées et peuvent être perdues lors
  d'une panne d'export. Elles ne remplacent ni l'audit SQL ni les offsets Kafka.
  Les frontières de spans et les limites sont décrites dans ADR 0012.
- Exercice reproductible : [runbook HTTP/Kafka](runbooks/http-kafka-tracing.md).
  Exécuter le scénario ciblé puis suivre les parentId dans spans.json ; comparer
  correlationId, traceId et spanId dans les logs, puis rejouer le scénario de panne.

### Vérifications et incidents résolus

- Maven a d'abord rencontré un refus d'écriture du sandbox dans le cache `.m2`.
  Le lancement autorisé hors sandbox a téléchargé les dépendances et packagé
  les applications. Aucun contournement ni modification des règles de sécurité.
- Premier lancement des six E2E : cinq passent, le nouveau scénario échoue avec
  NoSuchElementException lors de la lecture d'un span encore en attente d'export.
  La chaîne causale était déjà correcte. L'assertion utilise désormais une liste
  et attend sa taille, afin qu'Awaitility réessaie jusqu'à l'arrivée asynchrone.
  Journal conservé : `artifacts/m4-tracing-e2e.log`.
- Le cycle complet suivant `.\mvnw.cmd -B -ntp clean verify` affiche BUILD SUCCESS
  en 5:07, terminé le 2026-09-25 à 11:11:24 +02:00. Recompte indépendant des XML :
  87 Surefire + 21 Failsafe = 108 tests ; aucun échec, erreur ou ignoré.
  Les six E2E passent. Journal : `artifacts/m4-tracing-verify.log` ; rapports XML,
  logs des vrais JARs et spans.json dans les répertoires target/*-reports.
- Le nouveau scénario vérifie les quatre spans HTTP/API producer/processor
  consumer/rejection producer et leurs parentId, le lien log/span, les messages
  sans contexte ou avec traceparent invalide et le retry HTTP à IDs métier constants.
  Les comptes ledger/audit, offsets et quatre séries de métriques restent vérifiés.
- Le scénario PostgreSQL conserve santé/readiness/liveness et absence d'ack sur
  panne. Le récepteur retourne aussi 503, vérifié par un compteur de requêtes :
  après restauration SQL et redémarrage manuel, le replay réussit et avance
  l'offset malgré cette panne d'export. Les deux tentatives ont le même traceId
  et des spanId distincts. Aucun ack n'est conditionné par la réception d'un span.
- Validation locale du travail non commité basé sur `71b94ff`. Aucun nouveau
  commit, push, run CI, scan/publication d'image, déploiement Kubernetes ou cloud.
  M2 reste validé pour `cca00ee` seulement ; M4 reste en cours. Prochaine tâche
  exacte : exposer et tester les métriques bornées de progression/lag du processor.
