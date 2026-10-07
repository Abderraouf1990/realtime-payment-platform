# ADR 0015: Offline contract and evaluation corpus before model integration

Date: 2026-10-07
Status: Accepted for the first M5 slice; runtime controls are not implemented yet.

## Decision

Version an incident-assistant response schema, source manifest and 18 synthetic
evaluation cases in `docs/assistant/v1`. Keep these outside shared-contracts:
that module contains payment events and value types, not assistant policy.
This slice has no model client, server, credentials, retrieval service or tools.

## Trust boundary

The future gateway must authenticate the caller and authorize the incident scope
before retrieval or inference. Missing/expired identity or insufficient scope
returns a denied result without evidence retrieval or provider calls. Identity
comes from verified server context, never from a question, trace ID, document or
client-supplied authenticated flag. The corpus flags simulate gateway decisions.
Only an authorized incident-reader role may access approved incident evidence.
Log request ID, policy/version, source IDs and outcome; do not log raw questions,
retrieved text, credentials or model responses. Retention must be set before use.

Allow only versioned, allowlisted ADRs/runbooks with verified content hashes and
sanitized incident snapshots supplied by a trusted adapter. No arbitrary paths,
URLs, SQL, shell, Docker socket, Kafka consumer or write-capable credentials.
Even approved documents and telemetry are data, never instructions. Embedded
role changes, tool requests, links, encoded instructions and requests to reveal
secrets cannot override policy. Invalid provenance causes abstention. A document
hash establishes version integrity, not that every statement is current: later
ADRs supersede historical claims and unresolved contradictions require abstention.

Redact before storage, retrieval and provider transmission: deny raw payloads,
accounts, amounts, tokens, connection strings and arbitrary log fields. Use a
field allowlist and per-incident opaque aliases for any needed correlation.
Do not rely on the model to redact. Apply the same policy to output and logging;
reject an unsafe response rather than returning it. Corpus secret canaries are
invented adversarial fixtures, not approved model inputs or actual credentials.

## Response and abstention

Every diagnosis has a bounded code, a summary, findings referencing both evidence
IDs and approved source IDs, explicit uncertainty and read-only next checks.
References must resolve to evidence actually supplied for this request and to
retrieved manifest sources. No fabricated citations. Unavailable or stale data
cannot support a claim of current health or successful processing. Evaluate ages
relative to the request's evaluation time; timestamps in the future are invalid.
The 15-second freshness bound belongs to the M4 local exercise, not a production SLO.

Abstain when evidence is missing, stale, contradictory, outside scope or cannot
support the requested root cause. A supported observation such as unknown lag
can be diagnosed while explicitly abstaining from a root-cause claim. Denied
requests contain no findings. Neither counters, traces nor HTTP 202 prove a
committed Kafka offset or a unique payment. The output never executes recovery,
resets offsets, changes data, publishes events or makes payment decisions. It may
point an authorized human to the manual recovery runbook, without executable
commands or an automatic action channel.

## Tradeoff and validation

An offline contract makes assumptions and failure cases reviewable before model
or provider choice. It does not demonstrate authentication, redaction, retrieval,
prompt-injection resistance or model quality in a running assistant. Those need
implementation and adversarial evaluation later. The 18 cases are a development
regression corpus, not a statistically representative held-out benchmark.

JUnit checks the closed response schema subset, references, content hashes,
coverage and negative mutations during Maven verification. The corpus includes
reference answers and case-specific scoring requirements. See
[contract and evaluation protocol](../assistant/v1/README.md) for the learning
exercise and quality/latency/cost gates for later model runs.
