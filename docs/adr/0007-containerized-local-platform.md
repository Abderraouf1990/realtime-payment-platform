# ADR 0007: Build and run the payment applications in local containers

Date: 2026-09-19

Status: Accepted for roadmap milestone M1.

## Decision

Each application has a multi-stage Dockerfile with repository-root build context.
Build with the existing Maven wrapper and Temurin JDK 25; run the Boot executable JAR
on Temurin JRE 25. Base images are pinned by multi-platform digest. The context is an
allowlist of main sources, reactor POMs and wrapper inputs: no .env, Git metadata,
tests or host targets. Normalize wrapper line endings for Windows source checkouts.
BuildKit caches Maven downloads; no prebuilt host JAR or host JDK is required.

Packaging skips tests inside image builds because integration tests require Docker;
clean verify remains the separate full validation gate. Do not treat successful image
packaging as test evidence. Use the Compose acceptance exercise in addition to Maven.

Final images contain the JRE and application JAR, not Maven/source/build cache. Run
as UID/GID 10001. Compose uses read-only application root filesystems, writable /tmp,
no Linux capabilities and no-new-privileges. Exec-form Java entrypoints and Compose
init support signal forwarding; allow 30 seconds for graceful termination.

Compose configures Kafka at kafka:29092 and PostgreSQL at postgres:5432 independently
of their loopback host ports. The API receives no database configuration and has no
database dependency. Both apps wait for successful topic initialization; the processor
also waits for PostgreSQL health and applies Flyway on startup. Preserve named data
volumes and topic initialization. Runtime credentials come from environment variables;
the example defaults are for development only, with no real secrets or credentials in
Dockerfiles/build arguments. No image is published by this milestone.

The API exposes only loopback HTTP and has an Actuator healthcheck. At M1, the processor has
no HTTP server. Do not add a fake process-only healthcheck: running is not evidence
that its Kafka listener works. Validate through real payments, and defer listener
health monitoring to M4. Keep manual recovery and non-atomic rejection notification
semantics from ADR 0006 unchanged. An outbox remains deferred per ROADMAP.md.

2026-09-22 update: [ADR 0010](0010-processor-listener-health.md) adds HTTP listener
health to newly built processors. Published M2 images retain the original behavior.

## Tradeoff and learning exercise

Separate small Dockerfiles duplicate a few build lines but are easy to inspect and
produce independent application images without a new build module. An Ubuntu JRE is
larger than distroless but retains shell/curl tools useful for this deployment exercise.
Single-node infrastructure and development credentials are not production readiness.

Run scripts/verify-compose.ps1: it builds an isolated stack, proves non-root execution,
accepted/duplicate/rejected outcomes and the rejection event, then preserves data
across down/up and verifies new consumption. Cleanup always targets only its generated
Compose project. For manual learning, compare ledger/audit rows for an identical retry
and a negative payment; explain why HTTP 202 is not accounting completion and why an
already persisted rejection can generate another notification after replay.
