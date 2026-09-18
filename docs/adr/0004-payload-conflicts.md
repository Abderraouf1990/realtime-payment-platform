# ADR 0004: Business payload conflicts are explicit rejections

Date: 2026-09-18

Status: Accepted. Supersedes conflict-as-error handling in ADR 0002 and the
validation-before-lookup precedence in ADR 0003.

## Decision

After envelope validation (schema version, IDs and received timestamp), read the
ledger payload by transactionId. Compare accountId, amount, currency and type using
the pure `BusinessPayload.matches` method. Amounts use numerical comparison
(`10.0` equals `10.00`); other fields use exact equality. correlationId, receivedAt,
processedAt and the technical row ID never participate in business comparison.

An existing equal payload is an accepted DUPLICATE. An existing different payload
returns a rejected result with the sole reason PAYLOAD_CONFLICT, before evaluating
new-transaction business rules. Thus changing EUR to USD on an existing ID is a
conflict, not merely an unsupported-currency rejection. Missing business values
also differ from the required values in the existing row. Malformed envelopes and
unknown JSON enum strings remain contract/deserialization failures.

If no row exists, evaluate the normal business rules before writing. Use the existing
transactional INSERT with ON CONFLICT on the named unique transaction_id constraint.
If another writer inserted first, perform a second targeted read in that transaction
and use the same comparison. Return DUPLICATE or CONFLICT, never overwrite the row.
PostgreSQL uniqueness remains the final guarantee; the first lookup is not a lock
or proof that a later insert cannot conflict. The adapter assumes PostgreSQL's
default READ COMMITTED isolation and an append-only ledger managed by this service.
External mutation/deletion of ledger rows is unsupported.

The lookup is a snapshot of committed rows. A newly rejected invalid payload does
not insert or reserve its ID: if a valid event with that ID commits only after the
lookup, the rejection retains its ordinary rule reasons. Concurrent valid inserts
are protected by the constraint and post-insert comparison. Every conflict leaves
all existing columns, including first correlation and timestamps, unchanged.

## Logging and temporary acknowledgement

Log `transactionId`, the incoming `correlationId`, and `reason=PAYLOAD_CONFLICT` as
key/value fields. Do not log either payload, account data, amounts or raw errors.
Return normally after logging so RECORD acknowledgement commits the offset and
consumption continues. Conflicts are business rejections, not system exceptions.
The read/save transaction must finish successfully first. Database or commit failures
still propagate and stop the listener without acknowledging the failed event.

No durable conflict history is stored in this milestone. After offset commit the
group does not automatically replay the conflict; a crash before offset commit can
repeat the log. No retry topic or DLQ is added. Accepted inserts still require DB
commit before ack. These are at-least-once/idempotent-ledger semantics, not exactly once.

## Validation and limits

Unit tests cover every compared field, combined changes, numeric amount equivalence,
and application conversion of a post-insert CONFLICT outcome into a rejection.
PostgreSQL integration covers exact duplicates, changed correlation/timestamps,
each field conflict, and preservation of the complete original row. A test-only
historical database type exercises type comparison without extending the wire enum.
Kafka integration checks amount/currency conflict logs, offset advancement and
continued consumption, plus rollback/replay on commit failure. Concurrent writers,
network outages and process crashes remain untested scenarios.
