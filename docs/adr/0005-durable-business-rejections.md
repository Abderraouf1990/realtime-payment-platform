# ADR 0005: Durable and idempotent business rejection audit

Date: 2026-09-19

Status: Accepted. Replaces the temporary log-and-ack policy in ADRs 0003 and 0004.

## Decision

Flyway V2 creates `transaction_rejections` in the default application schema without
changing V1. Store the transaction/correlation/account IDs, original business values,
received timestamp, a UTC rejected timestamp supplied by the injected clock, and
the complete ordered list of reason codes. Never store stack traces or exception
messages. Technical/contract/deserialization failures remain outside business audit.

Use BIGINT identity for the technical ID, TIMESTAMPTZ for instants and TEXT[] for
reason codes. NUMERIC without scale preserves rejected decimals without rounding;
currency uses TEXT to preserve invalid lengths/case. Amount, currency and type can
be NULL because missing values are business rejection reasons. IDs and timestamps
are required. The reasons array must be nonempty with no null elements.

Define the business event as `(transaction_id, account_id, amount, currency, type)`.
A named `UNIQUE NULLS NOT DISTINCT` constraint deduplicates concurrent and repeated
rejections, including missing business values. PostgreSQL compares amounts numerically,
so -1.0 and -1.00 are equal. Correlation, timestamps and reasons are metadata, excluded
from the identity. INSERT uses ON CONFLICT on this constraint, DO NOTHING; preserve
the first committed correlation, times and reasons. A changed payload has its own
audit row even with the same transaction ID. No payload hashing is required.

The constraint's index starts with transaction_id and supports its lookup without
a redundant single-column index. Add a separate rejected_at index for time-based
audit queries. PostgreSQL's uniqueness semantics are described in the
[constraint documentation](https://www.postgresql.org/docs/17/ddl-constraints.html).

The plain application service calls a dedicated RejectionStore port for every
business rejection, including PAYLOAD_CONFLICT from either the initial ledger lookup
or the post-insert comparison. JdbcRejectionStore owns a JDBC transaction and returns
only after commit. Technical failures propagate; the Kafka listener stops without
acknowledging the failed record. Restore the database and restart the listener/process
to replay. There are still no automatic retries, retry topics, or DLQ.

On successful rejection persistence (including an already durable duplicate), the
application returns Rejected, the listener logs IDs/reasons and returns normally.
RECORD acknowledgement then commits the offset. Valid accepted events only write
ledger_transactions. Rejected events never insert or update the ledger. For a
payload conflict, the previous accepted row remains and the different incoming
payload is stored only in transaction_rejections.

## Audit scope and guarantees

This is an audit of first observed rejected business payloads, not a delivery log.
Later correlation IDs appear in logs but are not added to the row; repeated
timestamps are not retained.
If classification changes between deliveries (for example, a ledger entry appears
after an earlier invalid event), keep the first durable reason codes. Operational
logs reflect the current evaluation; historical reasons are immutable. Rejections
do not reserve transaction IDs: a corrected valid payload may later enter the ledger.
Rules/contract semantics are unchanged in this milestone.

A crash after rejection commit but before Kafka offset commit can replay the event;
the unique constraint prevents another audit row. This is at-least-once delivery
with idempotent persistence, not exactly once. Raw malformed JSON and unsupported
schema/enum values still stop consumption without an audit row. Audit retention,
access control and a read API are future work.

## Validation

Unit tests cover reason aggregation, audit field/time mapping, ledger/audit separation,
both conflict paths and propagation of rejection-storage failures. PostgreSQL tests
upgrade V1 to V2 without losing ledger data, check indexes, preserve decimal/null
values, deduplicate metadata-only retries, retain first reasons, and distinguish
changed business payloads. Kafka integration injects a deferred rejection COMMIT
failure, verifies rollback and unchanged offset, then verifies restart, replay and
duplicate rejection delivery. Existing ledger commit-failure tests remain in place.
