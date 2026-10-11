# ADR 0018: Opt-in OpenAI evaluation with a bounded campaign

Date: 2026-10-11
Status: Accepted; first local live campaign completed, M5 quality acceptance not met

Validation update 2026-10-11: the single approved campaign completed with 39
generations and USD 0.064752750 estimated cost. Preserve its durable marker.
The [agent review](../assistant/v1/live-evaluation-2026-10-11.md) separates
measured latency/cost from insufficient assertion and passage coverage;
human review and gateway authentication remain open.

## Decision and authorization

The user approved the direct OpenAI API, `gpt-5.4-mini-2026-03-17`, synthetic
filtered evidence and versioned ADR/runbook excerpts, USD 0.05 per call and USD
2.00 for this campaign. This does not authorize other campaigns, payment actions,
cloud provisioning, publication or Git push. ChatGPT Plus is not an API key.
Credentials are provided only through `OPENAI_API_KEY`, never command arguments,
context, source, reports or error messages.

The adapter uses Java HttpClient and the Responses API with `reasoning.effort=none`,
`store=false`, standard service tier, no tools, no conversation, no streaming,
no redirects and no retries. The fixed prompt includes the existing closed schema
and canonical templates. Generation is plain text; the existing strict local
ResponsePolicy remains the authoritative output boundary. We do not rely on a
provider JSON guarantee or relax the policy to accept a paraphrase.

## Cost and deadline

Pricing checked 2026-10-11: USD 0.75 input, 0.075 cached input, 4.50 output per
million tokens. These are estimates at standard rates, not billing receipts.
The approved launcher refuses execution after seven days without pricing review.

Before each attempt, reserve USD 0.05 from a shared process-local budget. Ask
`POST /v1/responses/input_tokens` to count the identical model, instructions,
input and reasoning settings. Generation is refused if uncached input cost plus
the cost of 2000 maximum output tokens exceeds USD 0.05. Output limits include
non-visible tokens. The count endpoint does not generate a diagnosis; reports
distinguish counting requests from generation requests. No tokenizer approximation
or cached discount is used for the preflight bound.

After complete valid usage, settle the reservation using the dated rates. Unknown
usage, HTTP failure, timeout or cancellation retains the full reservation. A
reported cost above the reservation records the overrun and halts future calls;
it cannot retroactively prevent an upstream billing discrepancy. Protocol failures
halt the campaign. Missing token fields stay unknown, not zero. Invalid provider
text is never written into a report; validated output and safe usage can be.

Both HTTP exchanges share a 30-second monotonic deadline (5-second connection
timeout); cancellation propagates to the HTTP future. Request and response bodies
are capped at 64000 bytes, with response limits applied during receipt. No local
cancellation guarantees that the provider stopped processing or billing.

The launcher permits at most 39 generations: three per each of the 13 admissible
cases, while five blocked cases make zero provider calls in every repetition.
Even with all reservations unknown, 39 x USD 0.05 = USD 1.95. A durable CREATE_NEW
marker under `artifacts/` prevents concurrent or subsequent launches of this
approved campaign, including after a crash or Maven clean. Do not delete that
marker to retry. Reconcile spend and obtain authorization for a new campaign.
The marker is a local operational guard, not an account-wide billing cap; another
checkout, manual deletion or unrelated API client is outside this guarantee.
Taxes, minimum credit purchases and other API activity are not covered.

## Evaluation and limitations

The report records effective prompt, source manifest, corpus, fixtures, schema and
Java source hashes, base commit, pricing, usage, known subtotal/unknown costs,
latency p50/p95, safe outputs, filtered contexts for review, retrieval misses and
all blocked/not-run rows. Three repetitions do not imply reproducibility of
stochastic generation. `codeAndStatusAgreement` is only a categorical comparison.
Human assertion scoring and cited-passage groundedness remain explicitly null.

The current typed corpus projections omit prose and contain same-time historical
conflicts. The restricted runtime also cannot diagnose recovered/replay_possible.
These limitations must remain visible in live results, not be tuned away using
gold answers. Offline fake success does not establish model quality. No gateway
authentication is implemented; fixture principals simulate trusted identity.

Tradeoff: an extra network token-count round trip and conservative failed-call
reservations reduce throughput and available budget, but avoid unsafe token/cost
guesses. Fixed language rejects useful paraphrases but keeps the disclosure and
action boundary inspectable. This is a bounded local experiment, not production
authorization or a production billing service.

## Sources and exercise

- [Model and pricing](https://developers.openai.com/api/docs/models/gpt-5.4-mini)
- [Counting input and output tokens](https://developers.openai.com/api/docs/guides/token-counting)
- [Count endpoint](https://developers.openai.com/api/reference/resources/responses/subresources/input_tokens/methods/count)
- [Reproducible offline and opt-in exercise](../assistant/v1/openai-evaluation.md)
