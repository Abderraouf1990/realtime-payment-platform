# ADR 0010: Processor listener readiness with manual recovery

Date: 2026-09-22
Status: Accepted. Extends ADR 0009 for newly built processor images.

## Decision

Add Spring MVC solely to expose Actuator health from the processor. The default
address is loopback (`PROCESSOR_ADDRESS=127.0.0.1`) and port is 8081
(`PROCESSOR_PORT`). There is no payment REST controller or recovery endpoint.
Only health is exposed; discovery, detailed global health and database health
checks are disabled. This health signal reads local state without database or
broker calls, so it remains accessible during their failure.

`KafkaListenerHealthIndicator` observes the registered `transaction-received`
container. Before ApplicationReadyEvent it reports OUT_OF_SERVICE/STARTING.
After startup it reports DOWN/MISSING if the expected container is absent,
DOWN/STOPPED if not running or not in its expected state (including abnormal
child failure), OUT_OF_SERVICE/PAUSED when a pause is requested, otherwise
UP/RUNNING. A manual restart can restore UP; failures are not latched.
Details consist solely of the allowlisted state, never exceptions, payloads,
transaction IDs, credentials or database/broker addresses.

| Endpoint | Meaning |
| --- | --- |
| `/actuator/health/listener` | Listener lifecycle, with the safe state detail; UP is HTTP 200, DOWN/OUT_OF_SERVICE is 503. |
| `/actuator/health/readiness` | Boot readiness plus listener health; a stopped listener makes it 503. |
| `/actuator/health/liveness` | Boot liveness only; remains 200 during a processing failure while the JVM/server is healthy. |

The HTTP server keeps the JVM available after consumption stops. Kubernetes
startup/liveness must use the liveness group, never the listener/readiness group.
Otherwise an automatic restart would change the existing manual recovery policy.
Readiness affects pod status; it does not pause Kafka consumption or repair it.
The existing error handler, RECORD acknowledgement and commit-before-ack policy
are unchanged. Restore the dependency, then explicitly restart the processor.

Helm exposes opt-in `processor.healthProbes.enabled`. Defaults remain false for
the immutable M2 digest, which has no HTTP server. Enabling requires a newly built
M4 processor image; the chart then binds health to the pod interface on 8081 and
adds startup/liveness/readiness probes. No external Service or Ingress is added.
No new image publication is implied by this change.

## Limits and tradeoff

This adds a web runtime and a small operational surface to keep diagnosis
available after listener failure. Local loopback is the default; cluster access
needs its own network/access controls before exposure beyond this demonstration.
RUNNING is a lifecycle signal, not evidence of partition assignment, broker or
database reachability, fresh polling, acceptable lag or successful payment flow.
An idle processor does not discover a PostgreSQL outage until a processing attempt
fails. Alerting, progress/lag metrics, traces and dashboards remain M4 work.

## Validation and exercise

Unit tests cover startup, missing/running/stopped/paused containers, abnormal
child state and manual recovery. The real-JAR E2E PostgreSQL outage scenario
asserts readiness/listener 503 while liveness remains 200 and the JVM stays alive,
an unchanged Kafka offset, no row after database recovery alone, then manual
processor restart, recovered health and one replayed row. Other E2E scenarios
retain their coverage and verify healthy startup; the valid-payment case checks
that env/configprops/beans/metrics endpoints are not exposed.
See the [incident runbook](../runbooks/processor-postgresql-outage.md).

References: [Spring Boot availability](https://docs.spring.io/spring-boot/reference/features/spring-application.html),
[Spring Kafka container lifecycle](https://github.com/spring-projects/spring-kafka/blob/v4.0.7/spring-kafka/src/main/java/org/springframework/kafka/listener/MessageListenerContainer.java).
