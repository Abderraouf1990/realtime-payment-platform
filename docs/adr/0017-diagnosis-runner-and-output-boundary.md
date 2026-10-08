# ADR 0017: Bounded diagnosis runner and restricted output

Date: 2026-10-08
Status: Accepted for the offline M5 runner; no real model configured.

## Decision

`DiagnosisRunner` first delegates authorization and evidence preparation to the
existing context builder. Denied/rejected context causes zero inference calls.
The provider-neutral port receives only the immutable context and policy version,
plus a timeout. It receives no principal, raw question, expected answers, gold
source IDs or evaluation rubrics. No network/model SDK or execution tool is added.
An adapter must implement this policy's wire format; the static `proposal` helper
specifies approved language for adapter authors and deterministic tests.

Each runner owns one daemon worker with no queue. Its configured timeout must be
between 1 ms and 30 s. Timeout cancels/interrupts the task and returns a fixed error;
no late result is returned. A non-cooperative adapter can occupy that worker until
it stops, so further calls fail unavailable rather than accumulating tasks.
Close interrupts work without indefinitely awaiting an uncooperative provider.
This bounds local waiting, not remote billing or forced termination. A real
adapter must additionally enforce network deadlines, cancellation and approved
spending limits. There are no retries. Reuse a runner rather than creating
unbounded instances. Concurrent calls may fail unavailable while its worker is busy.

## Output policy

Parse at most 16000 characters of JSON, reject duplicate keys and trailing content,
then validate the closed v1 response schema. The validator implements exactly the
keywords used by the versioned schema and rejects unsupported keywords; it is not
a general JSON Schema implementation. Arrays additionally have a 32-item limit.
Every finding must cite supplied evidence IDs and retrieved source IDs without
duplicates. Evidence supporting a diagnosis must be no more than 15 seconds old.
Contradictory same-time fields anywhere in the context block diagnoses. These
conservative demo checks do not establish general semantic entailment.

To make output confidentiality/action restrictions enforceable without pretending
regex can understand arbitrary prose, require exact approved text for summary,
claim, uncertainty, next checks and abstention reason. The provider may select a
supported diagnosis and references, but cannot return arbitrary natural language,
commands, credentials, URLs or payment decisions. Unexpected text rejects the
entire response; it is neither logged nor returned. Document excerpts remain
untrusted data, and no command in them creates an execution capability.

Supported diagnoses are idle, stopped_backlog, observation_unavailable,
counter_reset, attempt_not_commit and trace_incomplete, each guarded by supplied
typed facts. Idle/backlog additionally require fresh available lag and matching
listener state, with no unavailable observation in the bundle. There is exactly
one finding for a diagnosis. `unknown` requires the canonical abstention and no
findings. Provider-produced `denied` is invalid: access is decided before inference.
The broader v1 codes recovered/replay_possible are conservatively rejected by
this runner until adequate supporting projections and guards are implemented.

Membership checks do not prove that a cited passage entails the claim. Fixed
language prevents free-text disclosure but intentionally limits explanatory
quality; historical ADR conflicts and retrieval misses still need evaluation.
This is a stricter compatible output subset of ADR 0015, not a claim that all
schema-valid v1 reference responses are accepted by this runtime policy.

## Usage, cost and reporting

The adapter reports optional input/output/cached-input token counts. Invalid or
negative counts fail closed; cached input cannot exceed input. Null means unknown,
not zero. Estimated cost is computed only from complete counts and explicit
dated caller-supplied pricing/currency, with no hardcoded provider price. Missing
counts/pricing, timeout or provider failure leaves cost unknown. Valid usage is
retained even when a returned response is rejected. This is an estimate, not a
billing receipt or budget enforcement. Test prices are invented arithmetic fixtures.

Elapsed milliseconds use a monotonic clock across context building, waiting and
validation. The timeout applies to the inference wait, not the entire request.
Reports separate boundary results, retrieval misses, expected/returned status,
fake latency and null model/human scores. The corpus fake always abstains: 13
validated abstentions and five blocked contexts cannot be interpreted as model
diagnosis accuracy. No real provider call or quality/cost result is claimed.

See [exercise](../assistant/v1/diagnosis-runner.md). Next is an explicitly configured
real-model adapter and measured evaluation after model/data/budget selection.
