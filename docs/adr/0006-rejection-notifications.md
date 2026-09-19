# ADR 0006: Publish versioned rejection notifications after database commit

Date: 2026-09-19

Status: Accepted. Extends ADR 0005's acknowledgement policy.

## Decision

Add the framework-free shared record `contracts.v1.TransactionRejected`: schemaVersion,
transactionId, correlationId, reasonCodes (strings), rejectedAt. Schema version is 1;
timestamps serialize as UTC ISO-8601. Reason codes reuse the processor's stable names
without importing its business rules into shared-contracts. The payload excludes
account details, amounts, raw input and exceptions. Consumers must tolerate new codes.

The plain ProcessTransaction service calls the transactional RejectionStore, waits
for its proxy to return after PostgreSQL commit, then calls RejectionPublisher.
Do not wrap the service/listener in a database transaction: that would delay commit
until after publication and violate this ordering. The Kafka adapter waits for broker
confirmation (`acks=all`, producer idempotence, bounded timeout). Publish JSON without
Java type headers, keyed by transactionId, to `payments.kafka.rejected-topic`, default
transactions.rejected, configurable through TRANSACTIONS_REJECTED_TOPIC.

All business rejections, including payload conflicts and repeated rejections, follow
this path. Accepted events publish no rejection. A store/commit failure prevents
publication and input acknowledgement. A publication failure or timeout propagates
as a technical failure: the audit stays committed, the listener stops without input
acknowledgement. Operators restore service and restart for replay. Only successful
publication permits normal listener return and RECORD acknowledgement.

## Non-atomic boundary and replay semantics

There is no outbox or distributed transaction. A crash between database commit and
publication can leave a durable audit row without notification. Unacknowledged input
can be replayed while retained; there is no background scanner or publication marker
in the audit table. Always publish on a duplicate audit result as well, otherwise this
window would permanently suppress notifications on replay.

A send can succeed even when confirmation times out. A crash after send but before
input offset commit also causes replay and possible duplicate output. Kafka producer
idempotence does not make application retries exactly once. No atomicity or exactly-once
guarantee is claimed. A future transactional outbox is the next reliability objective.

Notifications describe the current rejected attempt, preserving incoming correlation
and current reasons/evaluation time. The audit retains the first business payload's
metadata and reasons as decided in ADR 0005. Retries can therefore emit notifications
with different metadata. transactionId is a routing key, not a globally unique rejection
identifier: separate conflicting payloads can share it. This minimal contract does not
provide downstream deduplication of distinct rejected payloads under the same ID.

## Validation

Unit tests cover wire round-trip without Java headers, field mapping, store-before-send
ordering, no publication on persistence failure, publication error/timeout/interruption,
and replay after a publication failure. Kafka/PostgreSQL tests inject rejection COMMIT
failure and verify no output and unchanged input offset, then successful restart/replay,
one audit row and repeated output keyed correctly on a configured topic. An injected
publisher failure leaves audit committed with no ack, then replay uses the real broker.
Existing HTTP-to-Kafka-to-PostgreSQL tests provision the output topic too.
