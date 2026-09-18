# ADR 0003: Deterministic business rejection with temporary log-and-ack policy

Date: 2026-09-18

Status: Accepted for the first business-validation milestone. Supersedes ADR 0002's
stop-on-error policy only for explicit business rejections.
The lookup/validation precedence and conflict handling below are superseded by
[ADR 0004](0004-payload-conflicts.md); temporary log-and-ack handling remains in use.

## Decision

Separate pure `TransactionRules` from application orchestration, JDBC persistence,
and the Kafka adapter. Require amount > 0, currency exactly EUR, and type TRANSFER.
Return all violated rules in that order; missing values fail their respective rule.
Do not normalize currency or extend the shared enum solely for testing.

The application first checks the event envelope (version, IDs, timestamp), then
evaluates rules before any ledger access. A failed rule returns `ProcessingResult.Rejected`
with immutable reason codes and never calls the store. A successful rule evaluation
is followed by amount-representation checks and persistence; only successful storage
returns `ProcessingResult.Accepted` with INSERTED or DUPLICATE. Technical failures
continue to propagate. Business checks take precedence over amount representation
and duplicate comparison: a rejected delivery does not query an existing ledger row.

The Kafka adapter logs rejections with transactionId, correlationId, and reason codes
only, then returns normally. With auto-commit disabled and RECORD acknowledgement,
this permits committing the rejected record's offset without a database write.
This is the intended completed operation for a rejection in this milestone.
Accepted events still require committed database work before acknowledgement.

No rejection table, retry topic, DLQ, or rejection event is introduced. Logs are
not a durable audit trail. A committed rejection is not automatically replayed by
that group; a crash before offset commit can repeat its log. Durable idempotent
rejection storage is the next milestone.

Malformed envelopes, unrepresentable accepted amounts, deserialization errors,
database failures, and conflicting valid duplicates still stop the listener without
acknowledging the failed record. TRANSFER is the only shared enum member today;
null tests the type rule, while unknown wire enum strings remain deserialization
errors. The API contract and its database isolation remain unchanged.

## Validation

Unit tests cover each rule and every combination, exact EUR matching, positive
boundaries, zero/negative amounts, missing values, and no persistence on rejection.
The Kafka + PostgreSQL test demonstrates rejection logging, zero ledger rows,
offset advancement, and subsequent consumption, alongside technical-failure replay.
