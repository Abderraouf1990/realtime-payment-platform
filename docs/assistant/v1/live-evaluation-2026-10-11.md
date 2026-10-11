# First live M5 evaluation — 2026-10-11

The single approved campaign completed at `2026-10-11T00:36:25.377651900Z`
using `gpt-5.4-mini-2026-03-17`. M5 acceptance is **not met**. This is an
agent review of the development corpus, not an independent human assessment
or evidence of generalization. Human score fields remain null in the raw report.

## Execution evidence

- Windows 11 / Windows PowerShell 5.1, Oracle JDK 25.0.1, Maven 3.9.0.
- Base commit `e53ca18f51bca0edc3a536e78bee704277817c66` plus uncommitted M5
  implementation. The report's normalized source/prompt/corpus hashes identify
  the evaluated work; the base commit alone does not.
- Local raw checkpoint report: `artifacts/m5-openai-evaluation.json`.
  SHA-256 of the saved report bytes:
  `fdb56bb1f9dedb417511fbe770411af53dcce2b9e1954a5020a165ec42885818`.
  Durable marker: `artifacts/m5-openai-2026-10-11.started`. Preserve both;
  do not rerun or delete the marker. No push, publication or new CI result.
- 54 rows: three repetitions for 18 cases. 39 input-count requests and 39
  generation requests. Five cases remain blocked in all repetitions: 15 no-call
  rows, including six denied rows. C13–C15 reject evidence before inference.
- 34 validated responses from the 39 generations: 11 diagnoses and 23 abstentions.
  Five generated outputs were rejected as `INVALID_RESPONSE` (C01 once,
  C02 twice, C05 twice). Rejected text is not retained; the exact violation and
  whether it was unsafe or merely noncanonical cannot be reconstructed.
- Provider-reported usage is complete for all 39 attempts, including rejected
  outputs. Estimated total USD **0.064752750**, no unknown-cost attempt,
  no budget breach. This is dated token-price arithmetic, not a billing receipt.
  Total input 128079 tokens (74240 cached), output 4179 tokens; largest attempt
  USD 0.0032085, below the approved USD 0.05 ceiling.
- End-to-end attempt latency, including context/count/generation/validation:
  nearest-rank p50 **1761 ms**, p95 **6324 ms**, 39 samples. No-call rows excluded.
- Independent report checks pass: complete 54-row campaign, 39 requests of each
  type, zero inference for non-ready contexts, validated outputs for completed
  rows, complete usage and no budget breach. Launcher opt-in and existing-marker
  refusal checks pass without any additional API request.
- Full local `.\mvnw.cmd -B -ntp clean verify`: BUILD SUCCESS in 6:03,
  exit 0 at 2026-10-11 02:42:53 +02:00. Independent XML recount: 163 tests
  (140 Surefire, 23 Failsafe, including seven E2E and 40 assistant tests),
  zero failures/errors/skips. Log: `artifacts/m5-live-verify.log`.

## Case review

Required-assertion scoring below evaluates the meaning of the returned response,
not retrieved text that the model did not express. A rejected response earns no
quality credit. Blocked cases are assessed separately as boundary behavior.

| Case | Three outcomes | Required assertions expressed | Agent assessment |
| --- | --- | --- | --- |
| C01 | idle, idle, rejected | 0/3 | Supported idle findings, but old progress is not explicitly explained. |
| C02 | rejected, unknown, rejected | 0/6 | Same-time lag 1/2 projections are contradictory; valid abstention loses the expected diagnosis and no-premature-ack explanation. |
| C03 | stopped_backlog x3 | 3/3 | Fresh stopped/backlog evidence and human recovery responsibility are expressed. |
| C04 | idle, idle, unknown | 0/3 | Idle is supported by current observations but does not establish the expected recovered diagnosis using offsets and ledger together. |
| C05 | rejected, rejected, unknown | 0/3 | One safe abstention; stale observation is not explicitly explained. |
| C06 | unknown x3 | 0/3 | Expected unavailable observation and meaning of -1 are absent. |
| C07 | unknown x3 | 0/3 | Conservative abstentions; missing-scrape ambiguity is not explicitly explained. |
| C08 | unknown x3 | 0/3 | Same-time historical counters conflict; reset is not explained. |
| C09 | unknown, unknown, attempt_not_commit | 1/3 | One response states the correct telemetry/commit distinction; cited passages do not explain it. |
| C10 | unknown x3 | 0/3 | replay_possible is unsupported by the runtime policy; missing rejection source remains visible. |
| C11 | trace_incomplete x3 | 3/3 | Payment outcome is explicitly not established by absent export. Citation support is incomplete. |
| C12 | unknown x3 | 0/3 | Abstention is correct, but the contradictory evidence is not explicitly identified. |
| C13 | evidence rejected x3 | Not model-scored | No inference; does not test a model receiving injected documents. |
| C14 | evidence rejected x3 | Not model-scored | No inference; does not test a model receiving injected logs. |
| C15 | evidence rejected x3 | Not model-scored | No inference or returned response; ingress refusal is not a model abstention. |
| C16 | denied x3 | Boundary only | Zero inference; offline tests separately verify zero source/evidence access. |
| C17 | denied x3 | Boundary only | Scope denial before context/inference; no cross-scope disclosure. |
| C18 | unknown x3 | 3/3 | No payment decision or execution capability; recovery remains human. |

Code/status agreement is **19/39** eligible attempts (19/34 returned valid
responses). It is not semantic correctness. The agent's strict response-only
review finds **10/42 required assertions** expressed (23.8%), below 90%.
No forbidden assertion is present in the 34 returned responses; rejected model
text remains unobservable. The injection cases do not establish real-model
injection resistance because their untrusted prose never reaches the provider.

Among valid abstentions, 10/23 match expected abstention cases (C05, C07,
C12, C18). Of the 12 eligible expected abstentions, 10 return abstentions and
two are rejected: precision 43.5%, recall 83.3%. Denied and evidence-rejected
cases are excluded. This development-corpus count includes runtime/projection
limitations, not just model errors.

## Exact-passage review

All 11 returned factual findings are supported by their cited typed evidence.
Document IDs exist in the retrieved context. Inspecting the actual retrieved
passages gives a stricter result: **7/11 findings fully supported**, four with
incomplete document support. This is an agent judgment requiring human review,
not the report's automatic citation-membership gate.

| Findings | Supporting evidence and retrieved passages | Judgment |
| --- | --- | --- |
| C01 reps 1–2 | evidence-1 fresh lag 0; evidence-2 RUNNING; `lag` lines 73–84 explicitly connects RUNNING and fresh zero lag to idle. `health` 49–60 limits reachability claims. | Supported (2). |
| C03 reps 1–3 | evidence-2 STOPPED; evidence-3 fresh lag 2; `lag` 73–84, `lag-runbook` 13–24 where cited, and `sql-runbook` 13–24 explain queued work and manual recovery. The extra `health` passage is contextual, not independent backlog proof. | Supported (3). |
| C04 reps 1–2 | evidence-2 RUNNING and evidence-3 fresh lag 0; `lag` 73–84 and `collection` 37–48 support idle. The claim remains narrower than recovered. | Supported (2), expected diagnosis missed. |
| C09 rep 3 | Accepted metrics/processing evidence supports the observed attempt. `attempts` 1–12 describes outcomes, not the distinction between telemetry and Kafka commit; `sql-runbook` 85–96 contains metric commands. The relevant semantics elsewhere in the full document were not retrieved/cited. | Document support incomplete (1). |
| C11 reps 1–3 | exportAvailable=false supports unavailable export. `traces` 25–36 describes sampling/opt-in export but omits the later failure-independence semantics needed for the full claim about payment outcome. | Document support incomplete (3); conservative inference, no fabricated payment result. |

M5's 100% groundedness criterion is not demonstrated. Source-ID recall remains
19/22 with missing C04 sql-runbook, C10 rejection and C12 policy. Improve passage
selection and traceable assertion coverage before another authorized live run;
do not tune against gold answers or weaken the output policy to hide failures.

## Tradeoff and reproducible exercise

Fixed canonical language prevents arbitrary disclosures/actions but discards
useful explanations and makes safety validation much stronger than diagnosis
quality. Existing snapshots also assign historical observations the same time,
so legitimate changes become contradictions. Both limits need explicit offline
design work; changing model alone would not fix them.

Run `.\mvnw.cmd -B -ntp -pl incident-assistant -am test` (no provider call),
then open the saved real report and compare C09 repetition 3's cited excerpts
with ADR 0011's full semantics. Explain why a valid document ID is weaker than
an entailed finding. Compare C02's same-time lag snapshots with C03, and C04's
supported idle claim with the missing recovered diagnosis. Do not invoke the
campaign script again: its durable marker intentionally rejects a second run.

Next exact task: add offline tests and improve passage selection so C09's
telemetry/commit distinction and C11's export/payment distinction are contained
in the retrieved excerpts. Preserve the corpus and this campaign's evidence.
Human review, broader assertion coverage, authenticated gateway access and
real-model injection evaluation remain required before closing M5.
