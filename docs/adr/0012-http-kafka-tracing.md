# ADR 0012: W3C trace context across HTTP and Kafka

Date: 2026-09-25
Status: Accepted. Extends M4 observability from ADR 0011.

## Decision

Use Boot 4.0.8's managed `spring-boot-starter-zipkin` (Micrometer/Brave)
in the API and processor. Enable observation on their auto-configured Kafka
templates and on the processor's record listener. HTTP server instrumentation
is supplied by Boot. Use W3C propagation; no trace fields enter shared-contracts,
database tables, business services or idempotency comparisons.

The causal chain is HTTP SERVER → Kafka PRODUCER → Kafka CONSUMER.
Rejection publication creates another PRODUCER child of the consumer span.
The consumer scope covers processing, database commit and broker confirmation
for a rejection. It is attempt telemetry, not proof of a committed Kafka offset.
The existing RECORD acknowledgement and stopping error handler are unchanged.

`traceId` and `spanId` from the scoped tracing MDC appear in the existing
processor JSON logs beside the independently validated business `correlationId`
and `transactionId`. No business IDs, payload, account fields or credentials are
added to span tags, baggage or metric labels. Framework observations retain
their standard transport attributes; this is not a global redaction guarantee.
No remote baggage fields or baggage-to-MDC fields are configured.

Sample 10% by default, configurable with `TRACING_SAMPLE_PROBABILITY`.
Propagation/log correlation remains available for unsampled traces, but a
collector will not receive those spans. Export is explicitly opt-in with
`TRACING_EXPORT_ENABLED=true`; `TRACING_ENDPOINT` defaults to local Zipkin's
`http://localhost:9411/api/v2/spans`. No collector infrastructure is required
for normal processing or added to Compose/Helm in this slice.

## Attempts, missing context and failures

- An HTTP retry without incoming context creates a new trace, even with identical
  transactionId/correlationId. If a caller reuses context, new server/producer
  spans belong to that trace. A trace ID is neither business identity nor trusted
  authentication evidence.
- Kafka replay reuses the context stored in the original record's headers;
  each delivery creates a new consumer span beneath the original producer.
  Manual restart can therefore add a later attempt to the same trace. There is
  no attempt count or exactly-once guarantee in the tracing metadata.
- Records with no usable trace context start a fresh consumer root. Framework
  scopes close between invocations, so adjacent records on the same partition
  do not inherit the previous record's trace. Invalid traceparent is ignored.
- Export uses the starter's asynchronous reporter. An unavailable exporter can
  lose spans; it must not gate payment persistence, broker confirmation or ack.
  The E2E outage exercise keeps export returning 503 during both the failed
  database attempt and successful manual replay. Fatal JVM failures and arbitrary
  custom observation handlers are outside this availability guarantee.

## Tradeoff and validation

Native framework instrumentation avoids a custom propagation protocol and
business wrappers, at the cost of framework-defined span boundaries and
best-effort, sampled evidence. Traces complement durable audit and offsets;
they cannot replace them. No JDBC child spans, dashboard or production collector
is claimed. M2 images still contain their previously published sources only.

The real-JAR E2E suite exports to a disposable loopback Zipkin protocol sink.
It checks explicit parent IDs across both processes, rejection publication,
log/span alignment, context-free and malformed-context records, repeated HTTP
intake, database failure, exporter 503 and restart/replay. Existing business,
health, bounded outcome metric and acknowledgement assertions remain active.
See the [reproducible exercise](../runbooks/http-kafka-tracing.md).

References: [Boot tracing](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html),
[Spring Kafka observations](https://docs.spring.io/spring-kafka/reference/4.0/kafka/micrometer.html).
Property names were also checked against the installed Boot 4.0.8 configuration metadata.
