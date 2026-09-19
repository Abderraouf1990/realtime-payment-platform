# Roadmap: AI and DevSecOps Demonstrator

Updated: 2026-09-19

## Primary objective

Build practical expertise in AI engineering and demonstrate senior DevSecOps capabilities through a deployable payment platform with an evaluated, secure incident assistant. Payment processing is the operational use case. Critical payment decisions remain deterministic.

## Approved milestone order

| ID | Milestone | Status | Acceptance evidence |
| --- | --- | --- | --- |
| M0 | Payment walking skeleton and reliability baseline | Completed baseline | Ledger, durable rejections, Kafka notifications and E2E; CI passed for 2937501. Known limitations remain in PROJECT_STATE.md. |
| M1 | Containerize API and processor | Next | From a clean clone, build and start the complete Compose stack; demonstrate accepted payment, duplicate, durable rejection and rejection event. Non-root applications, external configuration, no embedded secrets. |
| M2 | Secure image delivery | Planned | CI tests, code/dependency/secret analysis, image scans, SBOM and GHCR images traceable to commit; document blocking rules and any justified exceptions. |
| M3 | Local Kubernetes deployment | Planned | Reproducible Helm installation, resource requests/limits, restricted workload permissions, injected secrets, meaningful probes, controlled upgrade and rollback exercise. |
| M4 | Observability and incident exercises | Planned | Structured logs, metrics, distributed traces, listener-state monitoring, dashboards and runbooks; reproduce PostgreSQL outage, Kafka publication failure and malformed input. |
| M5 | Secure, evaluated incident assistant | Planned | RAG over versioned runbooks/ADRs; read-only incident evidence; sourced structured diagnosis and abstention; 15–20 evaluation cases including prompt injection; measure quality, latency and cost; authenticated access and redaction. |
| M6 | Optional cloud demonstration | Deferred | Choose provider and budget explicitly; reproducible deployment and teardown; no cloud provisioning implied by this roadmap. |

M1–M4 prepare the environment and evidence for M5. Keep these milestones bounded: AI must not be postponed indefinitely by additional payment features. Each milestone needs a reproducible demonstration, not a claim of production readiness.

## Current task: M1

Create Dockerfiles for transaction-api and transaction-processor and integrate them into the existing Compose infrastructure. Preserve topic initialization and database volumes. Use container-network addresses for application connections. Document prerequisites and clean-clone commands. Verify accepted, duplicate and rejected transactions through the running containers. Record commands, results and limitations.

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
