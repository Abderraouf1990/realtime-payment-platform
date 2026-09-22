# ADR 0011: Structured processing logs and attempt counters

Date: 2026-09-22
Status: Accepted. Extends the M4 observability surface from ADR 0010.

## Decision

Enable Spring Boot's Logstash JSON console format in the processor and use SLF4J
key/value fields for `event=payment.processing`. Each completed invocation of
`TransactionReceivedListener` reports one outcome: accepted, duplicate, rejected
or technical_failure. The application service, rules, stores, producer confirmation
and stopping error handler remain unchanged. The API's logging is outside this slice.

Processing logs contain transactionId/correlationId (only ASCII letters, digits,
underscore/hyphen, 1-64 characters; otherwise `unavailable`), outcome, reasonCodes,
partition and offset. Rejection reasons come from the business enum; technical
failures use the generic PROCESSING_FAILURE reason without the exception or cause.
No account, amount, currency, Kafka key/header or raw event is attached. Fields are
added per log event, without thread-local MDC, so one attempt cannot retain another
attempt's identifiers. Existing readable messages remain for operational searches.
The JSON contract concerns processing logs; JVM diagnostics and third-party logging
are not a promise of globally sanitized structured output.

Micrometer registers `payments.processing.attempts` with exactly four series and
one application label, `outcome`. No ID, exception class, reason combination, account,
topic, partition or offset becomes a metric label. All four series start at zero.
The read-only Actuator metrics endpoint is exposed alongside health on the existing
loopback port 8081; env/configprops/beans remain unexposed. Built-in framework meters
are also accessible and have their own label sets. No collector, Prometheus export,
dashboard, alert or external ingress is added. Published M2 digests are unchanged.

## Semantics and boundaries

| Outcome | Increment point |
| --- | --- |
| accepted | Application returns INSERTED after the ledger transaction commits. |
| duplicate | Application verifies an already persisted identical business payload. |
| rejected | Audit persistence and broker-confirmed rejection publication both succeed; a repeated audit still counts another attempt. |
| technical_failure | Application throws, including persistence or rejection publication failure; the sanitized error still stops consumption without acknowledging. |

These measure listener attempts, not unique transactions, durable audit counts,
Kafka acknowledgements or business throughput. They are recorded before the
container's RECORD ack: offset-commit failure is not counted here. Malformed JSON
that fails before listener invocation, polling/broker failures outside the listener,
and JVM crashes are outside these counters. A crash may leave persistence without
telemetry. A publication failure can leave an audit plus technical_failure; replay
can later count rejected. A post-commit replay can count duplicate. There is no
exactly-once telemetry guarantee.

Counters are in-memory per JVM and reset on restart. Logs/counters are best effort:
runtime recording failures are isolated so they cannot fail committed work or mask
a processing exception. They are not an audit substitute. IDs enable log searches;
bounded labels allow aggregation without unbounded time-series growth. Future
dashboards should use rates and handle resets, retaining PostgreSQL for audit.

## Validation and exercise

Unit tests cover outcomes, combined reasons, repeated attempts, invalid/null IDs,
sanitized errors, fixed metric labels and a counter failure that cannot change
processing success/failure. The five real-JAR E2E scenarios retain database and
offset checks while reading the actual metric HTTP endpoint and parsing JSON logs.
The PostgreSQL outage/replay scenario checks counter reset on JVM restart.
See the [incident runbook](../runbooks/processor-postgresql-outage.md) for queries.

References: [Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html),
[Micrometer counters](https://docs.micrometer.io/micrometer/reference/1.16/concepts/counters.html).
