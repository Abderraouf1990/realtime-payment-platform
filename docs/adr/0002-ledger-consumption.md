# ADR 0002: Transactional ledger consumption before Kafka acknowledgement

Date: 2026-09-18

Status: Accepted. Updates the processor implementation status in ADR 0001.

Business validation and the handling of explicit business rejections are updated
by [ADR 0003](0003-business-rejections.md). Technical failures still follow this ADR.

## Decision

Consume the shared version-1 `TransactionReceived` contract with an explicitly
configured Jackson JSON target, ignoring Java type headers. Both key and value
deserializers use Spring's ErrorHandlingDeserializer. Configure the topic and
consumer group externally, with local defaults. Do not give the API database access.

Keep the application service and ledger model independent of Spring. Use a ledger
store port and injected clock; a transactional JDBC adapter owns persistence and
duplicate detection. JDBC makes PostgreSQL's `ON CONFLICT` behavior explicit without
introducing JPA entities. Keep Flyway V1 unchanged; Hibernate only validates schema.

Insert with `ON CONFLICT ON CONSTRAINT uk_ledger_transactions_transaction_id DO NOTHING`,
then compare account, amount,
currency, and type when the insert was skipped. Equal business values are a successful
duplicate; different values throw. Preserve the first committed correlation and
timestamps. Log the incoming correlation for every processed delivery without
logging account details, payloads, or raw exception causes.

The named PostgreSQL unique constraint is the final arbiter of transaction identity.
Do not pre-check existence or catch all integrity errors as duplicates. The SQL
handles only the intended unique conflict without aborting the transaction; other
database failures propagate. An identical duplicate returns DUPLICATE normally.

Disable auto-commit and use RECORD acknowledgement with Spring Kafka's default
synchronous offset commits. The transactional store proxy commits before returning
to the listener; only a successful listener return permits acknowledgement. This
provides at-least-once delivery with idempotent database effects, not exactly once.

Stop the listener on processing/deserialization failure. Do not recover, skip,
acknowledge failed records, or introduce retry/DLQ topics. Operators must correct
the failure and restart; permanent invalid records remain blocking. The application
process may remain alive after its listener stops. Consumer health monitoring and
durable rejection outcomes are follow-up work. Missing topics fail startup; groups
without committed offsets use earliest retained records.

## Validation and limitations

Unit tests cover deterministic mapping, schema/shape/positive-amount checks and
storage failures. Pinned Kafka 4.1.1 + PostgreSQL 17.6 integration tests check
persistence, spoofed type headers, identical and conflicting duplicates. A dedicated
test publishes the exact same event twice, observes two committed Kafka records,
one ledger row, INSERTED/DUPLICATE outcomes and a running listener.
A deferred PostgreSQL constraint trigger rejects COMMIT after INSERT succeeds;
the transaction rolls back and the failed event's offset remains uncommitted.
Removing the fault and restarting the listener successfully replays the event.
This specifically verifies commit-error propagation through the transactional
store proxy before acknowledgement; it does not simulate a network outage.
This is a Kafka-to-ledger test; the API publication integration test is separate.
Concurrent duplicate deliveries and a process crash between DB and offset commits
are not directly exercised yet. Business rules and temporary rejection handling
are described in ADR 0003; durable rejection storage remains future work.

Reference: [Spring Kafka offset acknowledgement modes](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html).
