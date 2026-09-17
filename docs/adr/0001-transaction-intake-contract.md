# ADR 0001: Transaction intake identity and publication

Date: 2026-09-17

Status: Accepted for the initial intake slice; durable deduplication remains unimplemented.

## Context

The API publishes transactions without accessing the ledger database. Clients may
retry after a lost HTTP response or an uncertain Kafka send. The MVP must preserve
business identity across retries without claiming exactly-once Kafka publication.

## Decision

- Clients supply a globally unique `transactionId` and send the identical value in
  `Idempotency-Key`. Both use 1–64 ASCII letters, digits, underscores, or hyphens.
  A missing or malformed key returns 400; a different well-formed key returns 409.
- Every structurally valid submission makes one publisher call. Repeated requests
  may produce additional events with the same `transactionId`. Clients must reuse
  the same payload when retrying. The API has no durable request store and cannot
  currently detect changed payloads submitted with a previously used ID.
- The future processor must enforce database uniqueness by `transactionId`, compare
  duplicate payloads, and handle conflicts explicitly. It must commit persistence
  before acknowledging consumption. None of these processor guarantees exists yet.
- Publish JSON `TransactionReceived` with `schemaVersion: 1` on
  `transactions.received`. The Java contract lives in `contracts.v1` and has no
  framework dependencies. Fields are `schemaVersion`, `transactionId`, `correlationId`, `accountId`,
  `amount`, `currency`, `type`, and `receivedAt`. `receivedAt` describes each intake
  attempt, so it can differ on retries and must not be part of duplicate-payload
  comparison. `transactionId` is also the HTTP tracking identifier.
- Clients supply `correlationId` in the request body using 1–64 ASCII letters,
  digits, underscores, or hyphens. It identifies the originating workflow and is
  preserved in the event and acceptance response. Reuse it across retries and
  propagate it unchanged through subsequent service calls and derived events.
  It is tracing metadata, not an idempotency or partition key; multiple related
  transactions can share it. It must not be part of business-payload conflict checks.
  The stateless API does not enforce correlation consistency across retries.
- This extends the unreleased version-1 contract in place. Existing clients must
  add the required request field. Previously published events lack correlation
  metadata; adding the field does not retroactively make those events traceable.
  The processor now depends on the shared contract and tests deserialization of the
  field; actual consumer propagation and logging await processor implementation.
- Use `transactionId` as the Kafka record key. This groups retries under a stable
  key, without promising ordering across all transactions for one account. Changing
  the partition count can change key-to-partition assignment.
- Disable Java class-name headers on the wire. Consumers must select their event
  contract using the topic/schema version, rather than trusting producer class names.
- Enable Kafka producer idempotence and `acks=all`. Return 202 only after the send
  future succeeds. Synchronous failure, failed future, timeout, or interruption
  returns 503. A 503 is an uncertain outcome, not proof the event was absent.
  Kafka producer idempotence does not deduplicate independent HTTP submissions.
- Bound producer metadata/buffer waits to 5 seconds, delivery to 5 seconds, and the
  adapter's confirmation wait to 10 seconds. The send call's blocking phase precedes
  the confirmation wait. Interrupted waits restore the thread's interrupt flag.
- API validation covers shape and representation: required fields, restricted ID
  syntax, amount precision of 15 integer digits and at most 2 fractional digits,
  three uppercase currency letters, and the currently supported `TRANSFER` enum.
  Amount positivity and supported currencies are processor business rules; 202 does
  not mean a transaction has been validated or booked.
- Errors use Problem Details without echoing request values. The producer failure
  listener excludes message keys and values from its log output.

## Consequences

This slice supports confirmed intake and stable business IDs, not end-to-end
idempotency, cached HTTP responses, or exactly-once delivery. An unchanged request
can be retried safely only once the processor's deduplication is implemented.
No status lookup endpoint exists yet. Authentication and tenant-scoped IDs remain
future work; this API is currently a local demonstrator.

Topics must be provisioned externally for normal application startup. Tests and the
container-backed development launcher provision a three-partition, one-replica topic.
Production replication and minimum in-sync replica policy require a separate
deployment decision; `acks=all` on one replica is not multi-broker fault tolerance.

## Validation

Unit and MVC tests cover mapping, input errors, identity matching, failed sends,
confirmation waiting, timeout, and interruption. A pinned Kafka Testcontainer test
submits HTTP requests, consumes raw JSON, checks the contract and key, and checks
that retries retain the transaction identity. These do not validate ledger behavior.

Spring Boot 4.0.1 manages JUnit Jupiter 6.0.1. Spring Framework 7's `SpringExtension`
[requires Jupiter 6](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/support-classes.html).
The existing managed version is retained; `AGENTS.md`'s JUnit 5 requirement conflicts
with this stack and still needs alignment.
