param([switch]$RunApprovedCampaign)
$ErrorActionPreference = 'Stop'
if (-not $RunApprovedCampaign) { throw 'Explicit -RunApprovedCampaign is required; no API call was made.' }
if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) { throw 'Configure OPENAI_API_KEY locally, never in Git or chat.' }
$evaluationJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $evaluationJava)) { throw 'Java executable unavailable; configure JAVA_HOME with JDK 25.' }
$evaluationJavaVersion = (& $evaluationJava --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $evaluationJavaVersion -notmatch '(?m)^(java|openjdk) 25[\. ]') { throw 'JDK 25 is required; configure JAVA_HOME before running the campaign.' }
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repoRoot
try {
    if (Test-Path -LiteralPath 'artifacts/m5-openai-2026-10-11.started') {
        throw 'Campaign already claimed. Reconcile the existing report/spend before authorizing any new campaign.'
    }
    & .\mvnw.cmd -B -ntp -pl incident-assistant -am test
    if ($LASTEXITCODE -ne 0) { throw 'Offline tests failed.' }
    & .\mvnw.cmd -B -ntp -pl incident-assistant org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-DincludeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw 'Classpath preparation failed.' }
    $dependencyClasspath = (Get-Content -Raw 'incident-assistant/target/runtime-classpath.txt').Trim()
    $evaluationClasspath = (Join-Path $repoRoot 'incident-assistant/target/classes') + [IO.Path]::PathSeparator + $dependencyClasspath
    $baseCommit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record base commit.' }
    & $evaluationJava -cp $evaluationClasspath com.aayadi.payment.assistant.CorpusEvaluation $repoRoot $baseCommit --run-approved-m5
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation stopped; no automatic retry.' }
} finally { Pop-Location }
