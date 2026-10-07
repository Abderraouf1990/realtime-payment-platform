# ADR 0014: Local collection and incident dashboard

Date: 2026-10-07
Status: Accepted. Extends ADRs 0010–0013 for the bounded M4 demonstration.

## Decision

Add an opt-in `observability` Spring profile to the processor. It enables the
Boot-managed Prometheus registry and `/actuator/prometheus` on the existing
management surface. The registry accepts exactly six meter names: the four
consumer progress gauges, the four-outcome attempt counter, and one new listener
running gauge derived from the existing health indicator. Export has nine series;
only the attempt counter has an `outcome` label. No business IDs, payloads or
credentials are exported. Default application startup does not expose Prometheus.
The JSON metrics endpoint and listener behavior retain their previous semantics.

The opt-in Compose overlay builds the current application sources and adds
Prometheus 3.15.0 and Grafana 13.2.3, both pinned by registry digest. UI and processor
management ports bind to host loopback. Prometheus can access the payments and
monitoring networks; Grafana accesses only monitoring. Processor management binds
to its container interface for scraping. The monitoring network has higher gateway
priority so disconnecting the scrape network preserves access to the Prometheus UI.
This requires Docker Compose 2.33.1 or newer.
This is a trusted local demonstration,
not authentication, TLS or multi-tenant network isolation. Published M2 images
are not changed or reused as if they contained this instrumentation.

Prometheus scrapes every five seconds using text format (no trace exemplars) and
an additional metric/label allowlist. Its own `up`/scrape metadata remain available.
It evaluates two rules every five seconds, with a 15-second pending period:

| Rule | Condition |
| --- | --- |
| PaymentBacklog | Positive lag while scrape succeeds, observation is available and its age is between 0 and 15 seconds. |
| PaymentObservationUnavailable | Failed/missing scrape, missing required lag/availability/age series, unavailable observation or age outside the freshness bound. |

These short thresholds are exercise settings, not production SLOs. An old
progress age alone is not an alert: fresh zero backlog is normal when idle.
Unknown/stale lag never silently becomes healthy zero. Firing rules are visible
in Prometheus and Grafana; no Alertmanager, notification destination, webhook or
automatic restart is configured. Monitoring cannot change offset/recovery policy.

Grafana provisions a read-only dashboard and Prometheus datasource. Anonymous
access is Viewer-only on loopback; login/basic auth, sign-up, initial admin
creation, plugin preinstallation and analytics reporting are disabled. No login
credential is stored in the repository. Panels show scrape/listener/observation
availability, freshness, group lag, progress age, attempt rates, counter resets
and firing alerts. Rates apply `rate` to each counter before aggregation. Group
lag must not be summed across replicas. Logs and distributed traces stay in the
existing application/test evidence; this slice collects metrics only.

Both monitoring containers run as numeric non-root users with read-only roots,
dropped capabilities and no-new-privileges. Data uses writable tmpfs. Prometheus
retains at most two hours/64 MB of blocks (not a hard total WAL/RAM limit).
Restarting monitoring loses its history; provisioning recreates the dashboard.

## Tradeoff and validation

File provisioning and disposable storage make the local demo reproducible with
little infrastructure, at the cost of transient history and local-only trust.
There is no central log/trace backend, durable monitoring storage, external
notification or production security claim. These are not prerequisites for the
first read-only incident assistant.

The Java tests verify the exact export allowlist, bounded labels, existing
listener-health mapping and real HTTP exposition. Promtool tests cover idle,
backlog firing/resolution, unknown/stale/missing observations and counter resets.
`scripts/verify-observability.ps1` creates an isolated project, checks the live
dashboard/datasource, breaks/restores only scraping with no processor restart or
offset change, then causes a PostgreSQL outage and backlog. Database restoration
alone leaves the listener stopped. An explicit manual restart drains the backlog,
preserves ledger identity, advances offsets and demonstrates counter resets.
Snapshots and logs are retained under its ignored `artifacts/<project>` directory.

See the [exercise](../runbooks/local-observability.md). References:
[Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/),
[rule tests](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/),
[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/).
