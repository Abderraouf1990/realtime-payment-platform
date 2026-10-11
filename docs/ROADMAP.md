# Roadmap: AI and DevSecOps Demonstrator

Updated: 2026-10-11

## Primary objective

Build practical expertise in AI engineering and demonstrate senior DevSecOps capabilities through a deployable payment platform with an evaluated, secure incident assistant. Payment processing is the operational use case. Critical payment decisions remain deterministic.

## Approved milestone order

| ID | Milestone | Status | Acceptance evidence |
| --- | --- | --- | --- |
| M0 | Payment walking skeleton and reliability baseline | Completed baseline | Ledger, durable rejections, Kafka notifications and E2E; CI passed for 2937501. Known limitations remain in PROJECT_STATE.md. |
| M1 | Containerize API and processor | Completed locally and in CI | Image construction and Compose acceptance passed on GitHub for cca00ee: non-root, accepted payment, duplicate, durable rejection, Kafka event and down/up retention. See PROJECT_STATE.md. |
| M2 | Secure image delivery | Completed for cca00ee | Authorized manual run 35661616528 passed all gates and published both images. Registry manifests/configs independently match the tested bundle, source SHA and non-root configuration. See PROJECT_STATE.md and docs/evidence/m2-publication.json. |
| M3 | Local Kubernetes deployment | Completed locally | Isolated kind/Helm acceptance passed: M2 image digests, restricted non-root workloads, injected Secret, API/infra probes, payment outcomes, upgrade/rollback and PVC retention. Processor listener-health limitation is explicit in ADR 0009; no M3 CI claim. |
| M4 | Observability and incident exercises | Completed local baseline | Health, logs, counters, traces and committed lag; pinned local Prometheus/Grafana, Viewer dashboard and tested alerts. 119 Maven tests, seven rule cases and isolated scrape-loss/SQL-outage/manual-recovery exercise pass. See PROJECT_STATE.md; no M4 CI or production claim. |
| M5 | Secure, evaluated incident assistant | In progress | First live campaign completed: 39 generations, USD 0.064752750 estimated, p50/p95 1761/6324 ms. Agent review identifies insufficient assertion coverage and exact-passage support; human review, model injection evaluation and gateway authentication remain open. |
| M6 | Optional cloud demonstration | Deferred | Choose provider and budget explicitly; reproducible deployment and teardown; no cloud provisioning implied by this roadmap. |

M1–M4 prepare the environment and evidence for M5. Keep these milestones bounded: AI must not be postponed indefinitely by additional payment features. Each milestone needs a reproducible demonstration, not a claim of production readiness.

M5 acceptance remains: RAG over versioned runbooks/ADRs, read-only incident
evidence, sourced structured diagnosis and abstention, 15–20 evaluation cases
including prompt injection, measured quality/latency/cost, authenticated access
and redaction. The offline contract alone does not complete this milestone.

## Current task: M5, improve passage support after the first live evaluation

The user approved direct OpenAI / gpt-5.4-mini-2026-03-17, filtered synthetic
evidence and document excerpts, USD 0.05/call and USD 2.00 for one campaign.
The first measured campaign is complete and its durable marker must be preserved.
Quality acceptance is not met; improve C09/C11 passage selection offline next.
Retain the fake boundary tests and report agent/human quality separately.
No second campaign is authorized without spend reconciliation and new approval.
See PROJECT_STATE.md for exact criteria.
Deterministic payment decisions and manual recovery stay unchanged.
Keep new payment features and cloud deployment out of scope.
M2 publication was authorized only for cca00ee; further Git pushes and image
publications still require the user's approval.

## Deferred work

- Transactional outbox: retain as reliability backlog, not a prerequisite for the first incident assistant. Preserve the non-atomic publication limitation until implemented.
- Additional payment features, Kafka Streams, risk engine and Angular: only introduce them for an agreed measurable need.
- Cloud deployment: after local validation and an explicit budget/environment choice.

## Continuity and evidence

- This file owns the objective, milestone order and acceptance criteria.
- PROJECT_STATE.md owns current implementation, verified results, blockers and the single next task.
- ADRs own technical decisions and tradeoffs.
- Git commits preserve document history. Link tests, CI runs and commits as evidence; a green run proves only its own revision.
- On session start, read AGENTS.md, this roadmap, PROJECT_STATE.md and relevant ADRs; inspect actual code and worktree.
- On session completion, record what changed, what was verified, unresolved issues and the exact next task. Update milestone status only when acceptance evidence exists.
- Record explicit user reprioritization below, preserving previous decisions. Do not silently replace the goal with a technical backlog item.
- For learning, each milestone handoff should explain the key design tradeoff and one reproducible exercise the developer can perform.

## Decision history

- 2026-09-19: User reaffirmed AI expertise and DevSecOps demonstration as the primary objective.
- 2026-09-19: User approved containerization → secure delivery → local Kubernetes → observability → incident assistant. This supersedes the earlier outbox-first next objective. Outbox remains deferred reliability work.
- 2026-09-19: Roadmap and session continuity instructions recorded in Git; M1 is next, not yet implemented.
- 2026-09-21: M1 is committed as 722df65. M2 pipeline implemented locally; functional
  checks pass but dependency findings block delivery. No exception, push or publication
  authorized. Keep the approved milestone order and finish M2 before M3.
- 2026-09-21: Boot 4.0.8 plus Tomcat 11.0.26 remove the locally detected blocking
  image findings without exceptions. The 94 tests and Compose acceptance still pass.
  Next: remote CI and explicitly authorized GHCR delivery; M2 remains in progress.
- 2026-09-22: Verified run 35653382625 and its jobs/logs/artifact metadata for cca00ee.
  Remote validation is complete; publication was skipped on push. Next is explicitly
  authorized manual GHCR publication and digest verification, not M3.
- 2026-09-22: User explicitly authorized both M2 image publications. Manual run
  35661616528 succeeded for cca00ee; both registry digests/configs independently
  match the verified bundle. M2 is complete for that revision. M3 is next; no
  Kubernetes implementation or cloud deployment was performed during M2.
- 2026-09-22: M3 local kind/Helm acceptance passed using the published M2 digests.
  Configuration upgrade/rollback, business outcomes, restricted pods and PVC
  retention were demonstrated. Listener monitoring remains explicit M4 work;
  start with that signal and a PostgreSQL outage/manual-recovery runbook.
  No cloud resources, Git push or new publication were used.
- 2026-09-22: First M4 slice adds health for newly built processors and a PostgreSQL
  outage/manual-recovery runbook. Published M2 digests are unchanged; processor Helm
  probes are opt-in for a new image. M4 remains in progress; proceed to structured
  logs and outcome metrics after the health slice's checks. No new publication,
  push or cloud deployment is authorized by this implementation.
- 2026-09-22: Added structured processor logs and bounded outcome counters for
  listener attempts, with explicit replay/reset and pre-ack semantics. Health is
  committed as 10a6808. M4 remains in progress; the next slice is distributed trace
  context, preserving correlation IDs and payment semantics. No new publication,
  push or cloud deployment is implied.
- 2026-09-25: HTTP/Kafka W3C trace propagation is locally validated on work based
  on `71b94ff`: 108 Maven tests pass, including six real-JAR E2E scenarios with
  causal span checks, isolated contexts and export failure during manual replay.
  M4 remains in progress; continue with processor progress/consumer-lag metrics.
  The approved milestone order and publication authorization are unchanged.
- 2026-09-25: Group progress/lag is locally validated on work based on `6130a81`:
  117 Maven tests pass, including unassigned-group Kafka observation and seven
  real-JAR E2E scenarios. Stopped-listener backlog grows and drains after manual
  restart; unavailable broker observation is explicitly unknown. Continue M4
  with bounded local collection and a tested incident dashboard/alert. No change
  to milestone order, Git push or image publication authorization.
- 2026-10-07: The bounded M4 local demonstration passes on work based on `9aaceef`:
  119 Java tests, seven Prometheus rule cases and the isolated Compose exercise.
  Scrape-loss alert resolves without processor restart; SQL backlog drains only
  after explicit manual recovery. M4 is locally complete; proceed to the M5
  contract/evaluation corpus in the approved order. Central log/trace storage,
  production monitoring and external notifications are outside this local baseline.
  No new CI, push, cloud deployment or image publication is implied.
- 2026-10-07: First M5 slice locally validated on work based on `22f4dee`:
  read-only contract, hashed source manifest, closed response schema and 18
  synthetic cases with scoring protocol. All 123 Maven tests pass, including
  four artifact/negative-mutation tests. M5 remains in progress: next implement
  the offline authorized/redacted context builder, then model integration and
  measured evaluation. No priority change, provider call or CI claim.
- 2026-10-07: Offline context builder locally validated on work based on `96a242d`:
  134 Maven tests pass and both payment images build. All 18 cases exercised;
  13 ready contexts, two access denials, three evidence rejections. Retrieval
  finds 19/22 expected source IDs, with documented misses and no model-quality
  claim. Next is the diagnosis runner/output boundary; M5 remains in progress.
  No priority change, provider call, push, publication or new CI run.
- 2026-10-08: Diagnosis runner and restricted output boundary locally validated
  on work based on `1f3e476`: 141 Maven tests pass; all 18 corpus boundary checks
  pass, with 13 calls to an always-abstaining fake and five blocked contexts.
  Output language is intentionally fixed; model quality and real cost remain
  unmeasured. Next select model/data/budget, integrate one opt-in adapter and
  evaluate it. M5 remains in progress; no real provider call, push or CI claim.
- 2026-10-11: User approved the direct OpenAI API and pinned GPT-5.4 mini model,
  synthetic filtered context/document scope, USD 0.05/call and USD 2.00 for one
  campaign after clarification that this is not an account-wide billing cap.
  Opt-in adapter and repeatable evaluation harness pass 163 local Maven tests,
  including 40 assistant tests. API credentials/billing access are still needed. M5 remains in progress;
  no change to milestone order, no live result, push or publication is claimed.
- 2026-10-11: User requested continuation of M5. The approved single campaign
  completed with 39 generations and USD 0.064752750 estimated; no budget breach.
  Agent review finds assertion coverage and exact-passage support below acceptance,
  with human scores unmeasured. Preserve the marker; next improve C09/C11 passage
  selection offline. M5 remains in progress; milestone order is unchanged and
  no second campaign, push, publication or cloud deployment is authorized.
- 2026-10-11: User requested remediation of the CI image-scan failure for
  `81ce039` before continuing M5 passage work. Scope is corrected Java/OS
  dependencies, local Maven/Compose validation and exact-image security scans,
  with unchanged security thresholds and payment behavior. Resume C09/C11
  after the local delivery blocker is resolved. No push, publication or new
  model campaign is implied; milestone order remains unchanged.
- 2026-10-11: Local remediation passes 163 Maven tests, nine Python gate tests,
  exact-image Compose acceptance, source-secret checks and both Trivy image scans
  (zero HIGH/CRITICAL/secrets), with SBOMs and bound evidence. Correction is
  uncommitted on `81ce039`; remote CI remains to be demonstrated. Resume M5
  C09/C11 offline passage selection; no new milestone completion or publication.
