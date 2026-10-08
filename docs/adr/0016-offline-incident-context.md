# ADR 0016: Offline authorized context construction

Date: 2026-10-07
Status: Accepted. Implements the first boundary from ADR 0015, not a complete assistant.

## Decision and authorization

Add an independent `incident-assistant` Java library to the Maven reactor. It
depends on Jackson, with JUnit/AssertJ for tests; it has no Spring server, payment
module dependency, model client, database/Kafka client or command execution.
Payment images only need its POM in the build context so Maven can enumerate the
reactor; the library is not included in either payment application image.

`ContextBuilder.build` receives a trusted principal separately from request data.
It checks nonempty identity, expiry against an injected clock, incident-reader
role and the exact incident/service pair before calling either read-only port.
Denied requests return a fixed error with no context and zero adapter calls.
There is no identity verification protocol: a future gateway must construct this
principal from verified credentials, never deserialize it from caller JSON.
The two ports are evidence acquisition and approved document retrieval.

## Evidence boundary

The adapter supplies at most 16 typed snapshots. Scope must match; future dates,
empty snapshots, unknown fields, nested data, invalid types/ranges and unsupported
enum values fail closed before document retrieval. Sensitive fields such as raw
payload, password, amount, account and log message are rejected, not forwarded.
Correlation/trace IDs alone may enter the filter: bounded values become local
opaque aliases. Repeated correlations and trace parent relationships preserve
equality within a bundle; aliases are regenerated for each build, not persisted.
They must not be used to link incidents or as authentication evidence.

Output contains fixed incident/service aliases, evidence ID, adapter provenance,
observation time, projection kind and allowlisted fields. It does not include
principal identity, original scope strings, correlation values or raw question.
The question is reduced to a fixed English operational vocabulary used for
lexical retrieval; embedded commands or unknown words cannot become capabilities.
Adversarial text in an unsupported evidence field rejects the entire bundle.
This deliberately sacrifices partial diagnosis rather than accepting free-text
logs. No claim is made that a future model will resist all prompt injections.
Unknown lag and old timestamps remain unknown/old: construction is not diagnosis.

## Sources and bounds

`ManifestSources` lazily reads the deployment-controlled v1 manifest only after
access/evidence checks. Source IDs are unique, paths must match versioned ADR or
runbook paths and remain under the real repository docs directory. Traversal,
absolute paths and symlinks/junctions are rejected. SHA-256 is verified over UTF-8
text normalized from CRLF to LF for every entry, even unselected entries.
Unknown requested source IDs and any read/hash error fail closed without paths
or exception payloads in the returned result. The manifest is trusted deployment
configuration, not a caller-uploaded allowlist. The repository must remain
immutable during a read; this is not a defense against concurrent privileged
filesystem mutation or a compromised manifest/deployment.

Limits: 32 KiB manifest, 32 documents, 64 KiB per source, six excerpts, at most
12 lines and 1800 characters per excerpt. An oversized line is omitted rather
than truncated into a misleading quote. Each excerpt includes path, content hash
and exact line range, labelled UNTRUSTED_DOCUMENT_DATA. Documents can contain
runbook commands; they remain quoted data, never executable actions.

Ranking counts unique query terms in a chunk, selects one best chunk per document,
then sorts by descending score and source ID; ties within a document use the
first chunk. No embedding, model or reference answer participates in retrieval.
Empty matches produce an empty excerpt list, never invented references.

## Evaluation, tradeoff and remaining work

Typed fixtures project all 18 development cases without parsing free-text logs.
The evaluator reads gold source IDs only after constructing context. Access-denied
and evidence-rejected cases are excluded from retrieval recall and counted
separately. Source-ID recall does not measure passage relevance or entailment.
Hash integrity does not resolve historical statements superseded by later ADRs.

This small deterministic boundary is inspectable and needs no external service,
at the cost of English keyword coverage, omitted free-text details and incomplete
retrieval. It does not authenticate HTTP requests, produce diagnoses, implement
output redaction or measure model quality/latency/cost. Those remain M5 work.
See the [exercise and results](../assistant/v1/context-builder.md).
