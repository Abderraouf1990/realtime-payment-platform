# Exercise: follow HTTP intake through Kafka

Prerequisites: Java 25, Docker Desktop using Linux containers, Maven Wrapper.
Run from the repository root in PowerShell. No image publication or external
trace backend is needed; the test starts pinned Kafka 4.1.1/PostgreSQL 17.6,
the packaged applications, and a disposable loopback Zipkin protocol receiver.

```powershell
.\mvnw.cmd -B -ntp -pl payment-e2e-tests -am '-Dit.test=PaymentFlowIT#tracesLinkHttpKafkaAndRejectionAndIsolateUntracedEventsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
$evidence = 'payment-e2e-tests/target/failsafe-reports/tracesLinkHttpKafkaAndRejectionAndIsolateUntracedEventsIT'
$spans = Get-Content "$evidence/spans.json" -Raw | ConvertFrom-Json
$spans | Where-Object traceId -eq '1234567890abcdef1234567890abcdef' |
    Select-Object traceId, id, parentId, kind, name, @{n='service';e={$_.localEndpoint.serviceName}}
Get-Content "$evidence/processor.log" | Where-Object { $_.StartsWith('{') } |
    ConvertFrom-Json | Where-Object event -eq 'payment.processing' |
    Select-Object transactionId, correlationId, outcome, traceId, spanId
```

Follow each `parentId` to the previous span's `id`: server → API producer →
processor consumer → rejection producer. The server's parent is the synthetic
upstream span `1234567890abcdef`; that upstream span itself is not exported.
The log's consumer spanId must match the exported CONSUMER span.
The two raw Kafka messages have absent/invalid context and fresh trace IDs.
All messages retain `CORR-SHARED`, demonstrating its independence from tracing.
The repeated HTTP intake of `TX-NO-TRACE` has a new trace and outcome duplicate.

Repeat the dependency failure exercise:

```powershell
.\mvnw.cmd -B -ntp -pl payment-e2e-tests -am '-Dit.test=PaymentFlowIT#databaseOutageLeavesOffsetUncommittedUntilManualRestartAndReplayIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

This scenario also makes the trace receiver return 503. Failed database work
does not advance the offset; restoring PostgreSQL alone does not resume the
listener. Manual processor restart replays successfully while export is still
unavailable. Compare `TX-OUTAGE` in `processor.log` and `processor-restarted.log`
under that scenario's report directory: same traceId, different spanId. Missing
exported spans are expected during this injected failure. Logs, ledger, offsets
and health together supply evidence; trace completeness alone cannot do so.

Applications default to 10% sampling and export disabled. For a separately
started local receiver, set `TRACING_EXPORT_ENABLED=true`,
`TRACING_SAMPLE_PROBABILITY=1.0` and `TRACING_ENDPOINT` to its `/api/v2/spans`
endpoint in the application process environment. The E2E launcher sets these
properties explicitly and tests the real asynchronous export path. Default M2
Helm images do not contain this feature; this exercise uses freshly built JARs.
