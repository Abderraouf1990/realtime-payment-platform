# Diagnosis runner: offline exercise

```powershell
.\mvnw.cmd -B -ntp -pl incident-assistant -am test
Get-Content incident-assistant/target/diagnosis-evaluation.json
Get-Content incident-assistant/target/retrieval-coverage.csv
.\mvnw.cmd clean verify
```

`DiagnosisRunnerTests` checks zero calls on denied/rejected context; a supported
idle response; absence of raw identity/question in inference input; schema/type/
duplicate-key/trailing-data failures; fabricated citations; secrets and actions;
stale/unsupported/contradictory evidence; timeout against an interrupt-ignoring
fake; no queued calls; provider failures; invalid usage; dated synthetic pricing;
and unknown cost when usage is incomplete.

The existing corpus harness now also runs all 18 cases through the runner with
a fake that always proposes the same canonical abstention. It never reads gold
answers. Five contexts block inference (two denied, three rejected); the remaining
13 call the fake once. The report keeps expected/returned status side by side,
marks successful boundary handling and lists missing source IDs. Scores for model
and human quality and unknown cost remain null. A completed runner call is not a
correct diagnosis. The existing 19/22 source-ID retrieval recall remains separate.

For learning, inspect C01: the context is healthy idle but the fake abstains, so
boundaryPassed is true while expectedStatus and returnedStatus differ. Compare
C15, which never reaches inference. Then inspect the timeout test: after its
deadline the runner responds, but the fake still occupies the single worker until
released. Explain why cancellation cannot guarantee zero provider billing.

Only approved fixed language can leave this version of the output boundary.
Arbitrary prose is rejected, including benign paraphrases. This avoids claiming
that a keyword filter can reliably distinguish every secret or harmful command,
but limits usefulness. Citation membership and typed fact guards are not a human
groundedness review. See [ADR 0017](../../adr/0017-diagnosis-runner-and-output-boundary.md).

No real provider, credentials, prices or paid calls are configured. Actual model
evaluation needs an approved model/data scope/budget, a network adapter with
deadlines and spending caps, and the existing versioned evaluation protocol.
