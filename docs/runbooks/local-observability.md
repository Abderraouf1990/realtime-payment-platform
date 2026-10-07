# Local incident dashboard and alerts

Prerequisites: Java 25, Docker Desktop Linux containers, Docker Compose 2.33.1+ and
Windows PowerShell. Run from the repository root. Ports bind only to loopback;
the exercise starts its own project on random ports without touching an existing
development deployment. New application images are built locally, never published.

## One-command acceptance exercise

```powershell
.\scripts\verify-observability.ps1
```

It checks Prometheus configuration/rules, builds both apps, starts the stack and
checks the read-only provisioned Grafana dashboard and working datasource.
Healthy idle consumption must not alert. It submits an accepted payment and an
identical duplicate, disconnects only Prometheus's payments network and observes
PaymentObservationUnavailable firing. Reconnection resolves it with the same
processor start time and unchanged offsets.

Next it stops PostgreSQL, queues two payments and observes listener stopped,
lag increasing and PaymentBacklog firing. After database restoration, offsets
and ledger are still unchanged. The script explicitly performs manual processor
restart, verifies one row per accepted payment, committed offsets, lag zero,
resolved alerts and counter-reset history. No monitoring component performs recovery.

Evidence in `artifacts/payments-observe-<id>/` includes `endpoints.json`, the
provisioned `dashboard.json`, five timestamped metric/alert snapshots, Compose
logs and `result.txt`. Default cleanup removes only that generated project's
containers, networks and data volumes. Local built images and evidence remain.

To inspect the dashboard after the exercise, run with `-Keep`. Read the random
Grafana URL in `endpoints.json` and open `/d/payment-incident`. The script has
already recovered the processor. When finished, substitute its exact project name:

```powershell
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.observability.yml -p payments-observe-<id> down --volumes --remove-orphans
```

Do not substitute your normal development project: `--volumes` intentionally
removes this disposable exercise's database/broker volumes. No recursive host
directory deletion is involved.

## Persistent local session (disposable monitoring history)

For an explicitly chosen development project, start from a new terminal with no
port overrides. A distinct project name keeps its data separate:

```powershell
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.observability.yml -p payments-observability up -d --build --wait
```

Grafana: `http://127.0.0.1:3000/d/payment-incident` (Viewer, no login).
Prometheus: `http://127.0.0.1:9090/query` and `/alerts`.
Processor: `http://127.0.0.1:8081/actuator/prometheus` and health endpoints.
Override API_PORT, KAFKA_PORT, POSTGRES_PORT, PROCESSOR_METRICS_PORT,
PROMETHEUS_PORT and GRAFANA_PORT if these ports are already in use.
The monitoring tmpfs data disappears on restart; the dashboard is reprovisioned.
Use the same Compose arguments with `down` to retain payment data volumes, or
`down --volumes` only when intentionally discarding that project's data.

## Interpretation

- Check scrape availability and sample age before interpreting lag. Missing data
  means unknown. The 15-second threshold is deliberately short for this exercise.
- Fresh zero lag plus a running listener is idle/healthy even with old progress.
- A stopped listener and fresh positive lag point to queued work. Use the
  [PostgreSQL runbook](processor-postgresql-outage.md), inspect sanitized logs and
  correlate trace IDs using the [trace exercise](http-kafka-tracing.md).
- Attempt rates are not unique payments; the counters reset with the JVM. The
  dashboard uses per-series rate before aggregation and shows resets separately.
- Alerts are local firing states, not sent messages. No automatic repair is
  configured. Logs/traces are not centrally collected by this metrics stack.

Useful PromQL:

```promql
up{job="payment-processor"}
payments_consumer_lag
payments_consumer_observation_available
payments_consumer_observation_age_seconds
payments_consumer_listener_running
sum by(outcome) (rate(payments_processing_attempts_total[1m]))
sum(resets(payments_processing_attempts_total[10m]))
ALERTS{alertstate="firing"}
```

See [ADR 0014](../adr/0014-local-metrics-collection.md) for the pinned components,
export boundary and limits. Run `.\mvnw.cmd clean verify` for full Java validation;
the separate Compose exercise proves actual collection/dashboard/alert behavior.
