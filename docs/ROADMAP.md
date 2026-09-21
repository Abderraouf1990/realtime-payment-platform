# Roadmap: AI and DevSecOps Demonstrator

Updated: 2026-09-22

## Primary objective

Build practical expertise in AI engineering and demonstrate senior DevSecOps capabilities through a deployable payment platform with an evaluated, secure incident assistant. Payment processing is the operational use case. Critical payment decisions remain deterministic.

## Approved milestone order

| ID | Milestone | Status | Acceptance evidence |
| --- | --- | --- | --- |
| M0 | Payment walking skeleton and reliability baseline | Completed baseline | Ledger, durable rejections, Kafka notifications and E2E; CI passed for 2937501. Known limitations remain in PROJECT_STATE.md. |
| M1 | Containerize API and processor | Completed locally and in CI | Image construction and Compose acceptance passed on GitHub for cca00ee: non-root, accepted payment, duplicate, durable rejection, Kafka event and down/up retention. See PROJECT_STATE.md. |
| M2 | Secure image delivery | Completed for cca00ee | Authorized manual run 35661616528 passed all gates and published both images. Registry manifests/configs independently match the tested bundle, source SHA and non-root configuration. See PROJECT_STATE.md and docs/evidence/m2-publication.json. |
| M3 | Local Kubernetes deployment | Next | Reproducible Helm installation, resource requests/limits, restricted workload permissions, injected secrets, meaningful probes, controlled upgrade and rollback exercise. |
| M4 | Observability and incident exercises | Planned | Structured logs, metrics, distributed traces, listener-state monitoring, dashboards and runbooks; reproduce PostgreSQL outage, Kafka publication failure and malformed input. |
| M5 | Secure, evaluated incident assistant | Planned | RAG over versioned runbooks/ADRs; read-only incident evidence; sourced structured diagnosis and abstention; 15–20 evaluation cases including prompt injection; measure quality, latency and cost; authenticated access and redaction. |
| M6 | Optional cloud demonstration | Deferred | Choose provider and budget explicitly; reproducible deployment and teardown; no cloud provisioning implied by this roadmap. |

M1–M4 prepare the environment and evidence for M5. Keep these milestones bounded: AI must not be postponed indefinitely by additional payment features. Each milestone needs a reproducible demonstration, not a claim of production readiness.

## Current task: M3

Deploy the demonstrated payment flow to a local Kubernetes cluster using Helm and
the digest-pinned images published by M2. Configure resource requests/limits,
restricted permissions, injected secrets and meaningful probes. Verify the flow
and a controlled upgrade/rollback. Keep cloud deployment and new payment features
out of scope. M2 publication was explicitly authorized for cca00ee; further Git
pushes and image publications still require the user's approval.

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
