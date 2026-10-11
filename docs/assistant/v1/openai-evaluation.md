# Opt-in OpenAI evaluation

Approved 2026-10-11: direct OpenAI API, `gpt-5.4-mini-2026-03-17`, only filtered
synthetic evidence and retrieved versioned document excerpts, USD 0.05/call and
USD 2.00/campaign. Maximum 39 generations, three per eligible case, 30 seconds
per attempt including input-token counting, 2000 output tokens, no retry/tools.
The first campaign completed on 2026-10-11; see the
[agent review](live-evaluation-2026-10-11.md). Human review is still pending,
quality acceptance is not met, and the campaign must not be rerun.

## Offline exercise (no credentials, no external calls)

```powershell
.\mvnw.cmd -B -ntp -pl incident-assistant -am test
```

Read `incident-assistant/target/evaluation-preparation.json`: 54 rows, 39 fake
invocations, 15 blocked rows. Compare C01's abstention with its expected diagnosis,
and C15's zero invocations. Unknown cost stays null. The HTTP adapter tests use
only a loopback stub and a fake credential. Read the count-over-budget, stalled
body and interruption tests: explain why no generation follows an excessive
input count, and why unknown cost retains USD 0.05 after cancellation.

## Real campaign

An API account with billing and access to the pinned model is required. Configure
`OPENAI_API_KEY` only in the local process environment; never paste it in chat,
commit it or pass it as an argument. Do not turn on Java HTTP header/body logging.
The launcher checks for a key before invoking Maven or making a request.

From a local PowerShell terminal, a masked session-only prompt can be used:

Configure `JAVA_HOME` with JDK 25 first. The launcher uses its Java executable
instead of a potentially older `java` on PATH and checks the major version.

```powershell
$apiCredential = Read-Host 'OpenAI API key (local only)' -AsSecureString
$env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $apiCredential).Password
try {
    .\scripts\Invoke-AssistantEvaluation.ps1 -RunApprovedCampaign
} finally {
    Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue
    $apiCredential.Dispose()
}
```

This script runs offline tests, prepares the runtime classpath using the pinned
Maven dependency plugin, and invokes the Java harness. It does not print the key.
Setting an environment variable in another terminal does not modify the agent's
already-running process; run the command in the terminal holding the credential.

The campaign creates `artifacts/m5-openai-2026-10-11.started` before any network
request. A second launch is refused, even after Maven clean or a crash. Never
delete it to bypass this guard: reconcile existing usage and obtain a new campaign
authorization first. Pricing older than seven days also prevents execution.

Inspect `artifacts/m5-openai-evaluation.json`, which is checkpointed after every
row. Only filtered context and validated fixed-language output are retained, not
provider errors or rejected text. The durable marker prevents restarting even if
a crash interrupts report writing. Missing report rows after a crash mean unknown
spend and incomplete evaluation, not zero calls. No report containing real
results is committed or published automatically.

Review every diagnosed finding against its cited evidence and exact retrieved
passage. Apply the assertion/groundedness protocol in [README](README.md), retaining
unsupported diagnoses, missing retrieval and abstentions as limitations. Fill in
human scores only after review. Do not infer correctness from schema validation
or code/status agreement. Gateway authentication is still outside this harness.

The budget limits this campaign at the recorded standard token prices. It does
not cap account-wide charges, taxes, minimum credit purchases or other clients.
See [ADR 0018](../../adr/0018-opt-in-openai-evaluation.md) for the trust assumptions,
failure accounting and tradeoffs.
