# ADR 0009: Local Kubernetes with immutable application images

Status: Accepted
Date: 2026-09-22

## Decision

M3 uses a disposable, single-node kind cluster and the `deploy/helm/payments`
chart. The API and processor reference the independently verified M2 GHCR
digests. Kafka 4.1.1 and PostgreSQL 17.6 retain the existing development versions.
The kind node is Kubernetes 1.34.11, pinned by digest. No application code,
new image publication or cloud resources are required.

Each workload has resource requests/limits, a numeric non-root user,
RuntimeDefault seccomp, no privilege escalation, all capabilities dropped and
no mounted service-account token. The acceptance namespace enforces Kubernetes
Pod Security `restricted` at v1.34. Applications and PostgreSQL have read-only
root filesystems; Kafka and the Kafka initialization tools retain writable image
filesystems because the official image generates configuration at startup.

The chart references an existing Secret with `database`, `username`, `password`
keys. The acceptance script injects a randomly generated development credential
through stdin. Credentials are absent from Helm values/history and the API
receives no database configuration. This does not imply encrypted Secret storage
or network isolation: the local cluster has neither a TLS setup nor an enforced
NetworkPolicy.

PostgreSQL and Kafka use single-replica StatefulSets and separate PVCs. A
revision-specific Job creates both three-partition topics idempotently; application
init containers wait for them, and the processor also waits for PostgreSQL.
Deployments use Recreate: local simplicity and predictable resource use at the
cost of downtime. This is not a high-availability topology.

## Health and recovery

API startup/liveness and readiness probes use Actuator health groups. Readiness
checks application readiness state, not Kafka publication or downstream processing.
PostgreSQL startup/readiness use pg_isready, with TCP liveness; Kafka uses TCP
startup/liveness and a real topic-list request for readiness. None proves the
complete payment flow.

The immutable M2 processor image has no HTTP server or listener-health endpoint.
It deliberately has no misleading JVM-only health probe: Kubernetes Ready alone
does not establish a running consumer. Init checks gate startup, and actual HTTP,
database and Kafka assertions validate processing. A technical failure can leave
the JVM alive with a stopped listener; restore dependencies and restart the
processor manually. Listener-state monitoring remains an explicit M4 task.

## Verification and limits

`scripts/verify-kubernetes.ps1` owns a uniquely named kind cluster and private
kubeconfig. It validates server-side admission, runtime users and application
permissions, accepted/duplicate/conflicting/invalid payments and rejection events.
It upgrades a pod-template marker, processes another payment, rolls back to
revision 1 and processes another payment. Images remain unchanged; this proves
Helm configuration rollback, not application-version or database-schema rollback.

It recreates both infrastructure pods, checks retained rows and Kafka records,
manually restarts the processor and processes another payment. PVC retention
covers pod recreation, not deleting the kind cluster. There is no backup, disaster
recovery or exactly-once claim; the non-atomic rejection publication limitation
in ADR 0006 still applies. Finally, the script stops its port-forward and deletes
only its own cluster, including after failure, unless `-KeepCluster` is requested.

References: [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/),
[Kubernetes probe semantics](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/).
