# ADR 0013: Cached group lag and committed-offset progress

Date: 2026-09-25
Status: Accepted. Extends M4 health, attempt metrics and tracing.

## Decision

Observe the configured input topic and consumer group through a separate Kafka
Admin client. A scheduled task reads topic partitions, earliest/end offsets and
committed group offsets. It never accesses the listener's KafkaConsumer, joins
the group, changes offsets, or invokes payment processing. Configuration and
security settings come from Boot's KafkaAdmin; the client has a fixed identifier.
The required operations are read-only topic/group descriptions and offset reads.
Authenticated deployments must grant the corresponding Describe permissions.

The interval defaults to 5000 milliseconds (`payments.monitoring.interval`).
Each sample has a shared two-second wait budget and bounded Kafka request/API
timeouts. Samples run serially on Spring's scheduler, independently of the
listener and HTTP request threads. The Admin client closes with a one-second
budget. There is no new dependency or external collector in this slice.

Four cached gauges are exposed through the existing loopback Actuator metrics
endpoint. None has an application label: no group, topic, partition, client ID,
transactionId, correlationId, traceId, payload or credential labels are added.
Kafka/framework metrics already present retain their existing labels.

| Metric | Definition |
| --- | --- |
| `payments.consumer.lag` | Sum over input partitions of end offset minus committed next offset. Missing commits use earliest retained offset, matching the configured `auto-offset-reset=earliest`. `-1` means unavailable. |
| `payments.consumer.observation.available` | 1 after a successful complete sample, 0 initially or after a failed/invalid sample. |
| `payments.consumer.observation.age` | Seconds since the last complete successful sample; `-1` until the first one. Continues aging on failure. |
| `payments.consumer.progress.age` | Seconds since this JVM last observed a committed offset increase between comparable samples; `-1` before any observed advance or after a detected reset/topology change. |

Readers return cached values without network access. An immutable volatile
snapshot publishes each sample. Separate HTTP reads are not an atomic snapshot;
dashboards should tolerate transitions and always check availability/freshness.
Age uses the injected UTC clock and clamps backward wall-clock jumps to zero.

## Semantics and failure behavior

- Before assignment, a known topic can already have measurable lag. With no
  committed offsets, the baseline is earliest retained, not an invented commit.
  An empty topic has zero lag and unknown progress age; that is not a stalled
  consumer. Missing topic, authorization failure or unavailable coordinator means
  unavailable until a successful sample, not zero.
- Lag is group-wide uncommitted offset distance, including fetched/in-flight
  work. It survives local listener stop while the monitoring JVM is alive. It
  differs from native fetch-position lag, and is not an exact count of payment
  rows or messages (compaction and transactional/control records can create gaps).
- With several processor replicas, the same group metric is repeated per JVM.
  Do not sum it across replicas. Progress may have been made by another member.
  No local partition-assignment or unique-payment throughput claim is made.
- A first sample after JVM restart establishes a baseline without claiming past
  progress. Later committed advances update progress age. A rewind, disappearance
  of a previous commit or partition-set change resets that age to unknown.
  Retention advancing earliest without a commit does not count as processing.
- Offset reads are not atomic. A commit outside the observed retained range,
  inconsistent end/commit values, empty partitions or overflow invalidates the
  whole sample; the next sample can recover. No clamping to a reassuring zero.
- Broker/read failures set lag to -1 and availability to 0; the last successful
  observation/progress times remain historical. Check availability and age before
  interpreting progress. A dead JVM produces no fresh sample at all; collection
  and alerts will need a scrape/up signal as well.
- Sampling exceptions are isolated and no raw exception/payload is logged by
  the sampler. No health/readiness/liveness policy, ack, ledger operation or
  manual recovery behavior is changed. Monitoring cannot restart a listener.

## Tradeoff, evidence and exercise

A separate bounded Admin poll still works when the listener is stopped, and
measures committed rather than merely fetched progress. It costs periodic broker
queries and provides an approximate, delayed view. It is diagnostic evidence,
not an audit total or an automatic stall detector. RUNNING + fresh zero lag means
no observed backlog; old progress alone is not an alert. Fresh growing lag plus
STOPPED/PAUSED indicates queued work; growing lag with RUNNING needs inspection.

Unit tests cover unknown/idle states, multiple partitions, progress, retention,
read failures, recovery, resets and restart. Kafka integration covers a group
before any listener assignment and proves reads do not commit. Real-JAR E2E
checks idle health, backlog growth during SQL-induced listener stop, manual
drain/replay and unavailable broker metrics with responsive management endpoints.
The existing business/health/log/trace/ack assertions remain active.
See the [local exercise](../runbooks/consumer-progress-and-lag.md).

Reference: [Kafka Admin API](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/admin/Admin.html).
