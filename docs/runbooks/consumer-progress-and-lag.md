# Exercise: distinguish idle consumption, backlog and unavailable lag

Prerequisites: Java 25 and Docker Desktop with Linux containers. Run from the
repository root. Tests package the real applications and use Kafka 4.1.1 and
PostgreSQL 17.6. No image publication or external metrics service is needed.

```powershell
.\mvnw.cmd -B -ntp -pl payment-e2e-tests -am '-Dit.test=PaymentFlowIT#validHttpCreatesOneLedgerRowIT+databaseOutageLeavesOffsetUncommittedUntilManualRestartAndReplayIT+brokerOutageMakesLagUnknownWithoutBlockingManagementIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

The assertions read the actual HTTP gauges and compare them with Kafka offsets,
SQL rows and listener health. The idle scenario starts at zero lag with no observed
progress, then processes one payment and remains healthy at zero lag as progress
ages. The SQL outage stops the listener with an unchanged committed offset:
lag grows from 1 to 2 after another intake, and returns to 0 after manual restart.
Both queued payments are persisted once. Trace-export failure is still injected.
The broker outage instead produces availability 0 and lag -1; metrics/liveness
HTTP remain responsive. The test cleans up its isolated containers and JVMs.

For running source-built applications, inspect the existing management port:

```powershell
curl.exe -s http://127.0.0.1:8081/actuator/health/listener
curl.exe -s http://127.0.0.1:8081/actuator/metrics/payments.consumer.lag
curl.exe -s http://127.0.0.1:8081/actuator/metrics/payments.consumer.observation.available
curl.exe -s http://127.0.0.1:8081/actuator/metrics/payments.consumer.observation.age
curl.exe -s http://127.0.0.1:8081/actuator/metrics/payments.consumer.progress.age
```

| Evidence | Interpretation and action |
| --- | --- |
| Fresh available measurement, lag 0, RUNNING | No observed backlog. Old/unknown progress alone is normal when idle. |
| Fresh positive/increasing lag, STOPPED | Queued uncommitted work. Diagnose the failure, restore dependencies, then restart manually using the PostgreSQL runbook. |
| Fresh increasing lag, RUNNING or PAUSED | Inspect processing attempts, logs/traces and pause/assignment state. Lag alone cannot identify the cause or justify an automatic restart. |
| Availability 0, lag -1, aging observation | Measurement unavailable. Check Kafka connectivity, coordinator and read permissions; never interpret -1 as an empty backlog. |
| No HTTP response | No current JVM observation. A future collector needs its own target-availability signal. |

The interval defaults to five seconds; E2E use 500 ms. Allow at least one sample
interval plus the two-second query budget before interpreting a transition.
Production thresholds are not prescribed here. The four new gauges have no labels
and represent one configured topic/group; do not sum across processor replicas.
Lag counts offset distance rather than unique payments. On restart, progress age
is unknown until a new advance is observed; lag comes from durable broker offsets.
See [ADR 0013](../adr/0013-consumer-progress-and-lag.md) for retention/reset limits.

The [PostgreSQL incident runbook](processor-postgresql-outage.md) remains the
recovery procedure. Published M2 images do not contain these metrics; build from
the current sources. Collection, dashboards and alerts are the next M4 slice.
