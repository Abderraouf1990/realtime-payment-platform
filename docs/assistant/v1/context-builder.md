# Offline context builder

Implemented in `incident-assistant`, separate from payments. There is no CLI
accepting credentials and no server/provider call. The public Java boundary is
`ContextBuilder.build(Principal, Request)`; the principal and two read-only
adapters are trusted wiring, not client-controlled objects. See
[ADR 0016](../../adr/0016-offline-incident-context.md) for limits and trust assumptions.

## Exercise

```powershell
.\mvnw.cmd -B -ntp -pl incident-assistant -am test
Get-Content incident-assistant/target/retrieval-coverage.csv
.\mvnw.cmd clean verify
```

`ContextBuilderTests` demonstrates zero adapter calls for missing/expired identity,
wrong role or incident/service scope; rejection of sensitive/injected fields;
opaque aliases; future/invalid observations; fixed error messages; deterministic
retrieval; exact excerpt line references; unknown IDs, traversal, duplicate IDs,
oversized files and source tampering. Filesystem mutation tests use temporary
copies and never alter the repository's approved sources.

`CorpusRetrievalTests` runs all 18 cases using `context-fixtures.json`. This file
is a hand-authored structured projection, not an ingestion parser. The evaluator
simulates trusted principal decisions from the original corpus flags. Neither
those flags, expected answers, gold source IDs nor rubrics enter the builder.
Only question terms, typed evidence and the verified document manifest do.

Learning exercise: inspect C10 in the generated report, then read its returned
source IDs and the reference sources in `cases.json`. Explain why finding a
related document is insufficient to support the non-atomic rejection-publication
claim. Inspect the alias test to see how correlation equality survives without
exposing the identifier. Run the tamper test to see that even an unselected
modified document rejects the request.

## Coverage and limitations

The focused run produces 13 ready contexts, two access denials (C16/C17) and
three evidence rejections (C13/C14/C15). C13/C14 contain deliberately unsupported
message fields; rejecting the projection is stricter than answering the original
diagnosis question. C15 contains invented secret/account canaries. These five
cases produce no source reads. They are boundary checks, not successful model
diagnoses or prompt-injection evaluation scores.

On the 13 ready cases, 19 of 22 expected source IDs are retrieved (86.4% micro
recall), and all 13 include at least one expected source. Missing references:
C04 sql-runbook, C10 rejection, C12 policy. These misses are recorded rather than
hidden by supplying oracle IDs to retrieval. No passage-entailment, precision,
diagnosis correctness, model latency or cost result is claimed.

The builder returns `ready`, `denied` or `rejected`; these are context-construction
statuses, distinct from the assistant response schema's diagnosis/abstention
statuses. Empty or stale evidence may produce a ready context, with its original
limitations preserved; downstream diagnosis must abstain when unsupported.
Raw log ingestion, HTTP authentication, stable cross-request aliases, durable
storage, actual model inference and output validation remain unimplemented.
