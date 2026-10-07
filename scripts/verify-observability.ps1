param([switch]$Keep)

# Each run owns only its generated Compose project, networks and volumes.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$project = 'payments-observe-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$evidence = Join-Path $repoRoot "artifacts/$project"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$composeArgs = @('--project-directory', $repoRoot, '--file', (Join-Path $repoRoot 'docker-compose.yml'),
    '--file', (Join-Path $repoRoot 'docker-compose.observability.yml'),
    '--env-file', (Join-Path $repoRoot '.env.example'), '--project-name', $project)
$testEnv = @{
    API_PORT='0'; KAFKA_PORT='0'; POSTGRES_PORT='0'; PROCESSOR_METRICS_PORT='0'; PROMETHEUS_PORT='0'; GRAFANA_PORT='0'
    POSTGRES_DB='payments'; POSTGRES_USER='payments'; POSTGRES_PASSWORD='payments_observe_only'
    KAFKA_CONSUMER_GROUP='observability-exercise'; TRANSACTIONS_RECEIVED_TOPIC='transactions.received'
    TRANSACTIONS_REJECTED_TOPIC='transactions.rejected'; KAFKA_CLUSTER_ID='MkU3OEVBNTcwNTJENDM2Qk'
}
$savedEnv = @{}
$disconnected = $false
$prometheusId = $null

function Invoke-Docker([string[]]$CommandArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & docker @CommandArgs 2>&1; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previous }
    if ($code -ne 0) { throw "Docker command failed ($code): $($output -join "`n")" }
    return $output | ForEach-Object { "$_" }
}
function Compose([string[]]$CommandArgs) { Invoke-Docker (@('compose') + $composeArgs + $CommandArgs) }
function Assert-Equal($actual, $expected, [string]$message) {
    if ($actual -ne $expected) { throw "$message : expected $expected, got $actual" }
}
function Wait-For([scriptblock]$check, [string]$message) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        try { if (& $check) { return } } catch { $lastError = $_.Exception.Message }
        Start-Sleep -Milliseconds 1000
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $message. $lastError"
}
function BaseUrl([string]$service, [string]$port) {
    'http://' + ((Compose @('port', $service, $port) | Select-Object -Last 1).Trim())
}
function Query([string]$expression) {
    $result = Invoke-RestMethod "$promUrl/api/v1/query?query=$([uri]::EscapeDataString($expression))" -TimeoutSec 5
    Assert-Equal $result.status 'success' 'Prometheus query'
    return @($result.data.result)
}
function Value([string]$expression) {
    $rows = @(Query $expression)
    if ($rows.Count -ne 1) { return [double]::NaN }
    return [double]::Parse($rows[0].value[1], [Globalization.CultureInfo]::InvariantCulture)
}
function Firing([string]$name) {
    $response = Invoke-RestMethod "$promUrl/api/v1/alerts" -TimeoutSec 5
    return @($response.data.alerts | Where-Object { $_.labels.alertname -eq $name -and $_.state -eq 'firing' }).Count -gt 0
}
function Sql([string]$query) {
    return ((Compose @('exec','-T','postgres','psql','-U','payments','-d','payments','-v','ON_ERROR_STOP=1','-Atc',$query)) -join "`n").Trim()
}
function OffsetTotal {
    $output = Compose @('exec','-T','kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','kafka:29092',
        '--group','observability-exercise','--describe')
    $total = 0L
    $found = 0
    foreach ($line in $output) {
        if ($line -match '^observability-exercise\s+transactions\.received\s+\d+\s+(\d+)\s+') {
            $total += [long]$Matches[1]; $found++
        }
    }
    if ($found -eq 0) { throw 'No committed offsets observed' }
    return $total
}
function Submit([string]$id) {
    $body = @{transactionId=$id; correlationId="CORR-$id"; accountId='ACC-OBSERVE'; amount=42.00; currency='EUR'; type='TRANSFER'} | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -UseBasicParsing "$apiUrl/api/v1/transactions" -Method Post -ContentType 'application/json' `
        -Headers @{'Idempotency-Key'=$id} -Body $body -TimeoutSec 20
    Assert-Equal $response.StatusCode 202 'intake'
}
function Snapshot([string]$stage) {
    @{ stage=$stage; at=[DateTime]::UtcNow.ToString('o');
       samples=@(Query '{__name__=~"payments_.*|up|ALERTS",job="payment-processor"}');
       alerts=(Invoke-RestMethod "$promUrl/api/v1/alerts" -TimeoutSec 5).data.alerts
    } | ConvertTo-Json -Depth 15 | Set-Content (Join-Path $evidence "$stage.json") -Encoding UTF8
}

try {
    foreach ($name in $testEnv.Keys) {
        $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $testEnv[$name], 'Process')
    }
    Compose @('config','--quiet') | Out-Null
    Compose @('run','--rm','--no-deps','--entrypoint','/bin/promtool','prometheus','check','config','/etc/prometheus/prometheus.yml') | Write-Output
    Compose @('run','--rm','--no-deps','--workdir','/etc/prometheus','--entrypoint','/bin/promtool','prometheus','test','rules','rules.test.yml') | Write-Output
    Compose @('build','transaction-api','transaction-processor') | Write-Output
    Compose @('up','-d','--no-build','--wait','--wait-timeout','180') | Write-Output
    $apiUrl = BaseUrl 'transaction-api' '8080'
    $processorUrl = BaseUrl 'transaction-processor' '8081'
    $promUrl = BaseUrl 'prometheus' '9090'
    $grafanaUrl = BaseUrl 'grafana' '3000'
    $prometheusId = ((Compose @('ps','-q','prometheus')) -join '').Trim()
    $processorId = ((Compose @('ps','-q','transaction-processor')) -join '').Trim()
    $started = ((Invoke-Docker @('inspect','--format','{{.State.StartedAt}}',$processorId)) -join '').Trim()
    @{project=$project;api=$apiUrl;processor=$processorUrl;prometheus=$promUrl;grafana=$grafanaUrl} |
        ConvertTo-Json | Set-Content (Join-Path $evidence 'endpoints.json') -Encoding UTF8

    Wait-For { (Value 'up{job="payment-processor"}') -eq 1 -and (Value 'payments_consumer_listener_running') -eq 1 `
        -and (Value 'payments_consumer_observation_available') -eq 1 -and (Value 'payments_consumer_lag') -eq 0 } 'healthy idle collection'
    Wait-For { (Invoke-RestMethod "$grafanaUrl/api/dashboards/uid/payment-incident" -TimeoutSec 5).dashboard.uid -eq 'payment-incident' } 'provisioned dashboard'
    $dashboard = Invoke-RestMethod "$grafanaUrl/api/dashboards/uid/payment-incident" -TimeoutSec 5
    Assert-Equal $dashboard.meta.canEdit $false 'anonymous dashboard is read-only'
    foreach ($panel in $dashboard.dashboard.panels) {
        foreach ($target in $panel.targets) { Query $target.expr | Out-Null }
    }
    $proxy = Invoke-RestMethod "$grafanaUrl/api/datasources/proxy/uid/payments-prometheus/api/v1/query?query=up" -TimeoutSec 5
    Assert-Equal $proxy.status 'success' 'Grafana datasource can query Prometheus'
    $dashboard | ConvertTo-Json -Depth 30 | Set-Content (Join-Path $evidence 'dashboard.json') -Encoding UTF8
    Start-Sleep -Seconds 20
    Assert-Equal (Firing 'PaymentBacklog') $false 'idle backlog alert'
    Assert-Equal (Firing 'PaymentObservationUnavailable') $false 'idle observation alert'
    Snapshot 'idle'
    $scrape = (Invoke-WebRequest -UseBasicParsing "$processorUrl/actuator/prometheus" -TimeoutSec 5).Content
    $samples = @($scrape -split "`n" | Where-Object { $_ -and !($_.StartsWith('#')) })
    Assert-Equal $samples.Count 9 'bounded exported series'
    foreach ($line in $samples) {
        if ($line -notmatch '^payments_(consumer_(lag|observation_available|observation_age_seconds|progress_age_seconds|listener_running)|processing_attempts_total\{outcome="(accepted|duplicate|rejected|technical_failure)"\}) [-0-9.E]+\s*$') {
            throw "Unexpected exported metric: $line"
        }
    }
    foreach ($service in @('prometheus','grafana','transaction-processor')) {
        $id = ((Compose @('ps','-q',$service)) -join '').Trim()
        $inspect = ((Invoke-Docker @('inspect',$id)) -join "`n") | ConvertFrom-Json
        Assert-Equal $inspect[0].HostConfig.ReadonlyRootfs $true "$service read-only root"
        foreach ($binding in $inspect[0].NetworkSettings.Ports.PSObject.Properties.Value) {
            foreach ($item in $binding) { Assert-Equal $item.HostIp '127.0.0.1' "$service loopback binding" }
        }
    }

    # Break only scraping. Alert fires/resolves with no processor restart or offset change.
    Submit 'TX-OBSERVE-VALID'
    Wait-For { (Sql "SELECT count(*) FROM ledger_transactions WHERE transaction_id='TX-OBSERVE-VALID'") -eq '1' } 'first ledger entry'
    Submit 'TX-OBSERVE-VALID'
    Wait-For { (Value 'payments_processing_attempts_total{outcome="duplicate"}') -eq 1 } 'duplicate observed before reset'
    $offset = OffsetTotal
    Invoke-Docker @('network','disconnect',"${project}_payments",$prometheusId) | Out-Null
    $disconnected = $true
    Wait-For { Firing 'PaymentObservationUnavailable' } 'scrape-loss alert firing'
    $alerts = (Invoke-RestMethod "$promUrl/api/v1/alerts" -TimeoutSec 5).data.alerts
    Assert-Equal @($alerts | Where-Object { $_.labels.alertname -eq 'PaymentObservationUnavailable' -and $_.state -eq 'firing' }).Count 1 'one observation alert per target'
    Snapshot 'scrape-lost'
    Invoke-Docker @('network','connect',"${project}_payments",$prometheusId) | Out-Null
    $disconnected = $false
    Wait-For { (Value 'up{job="payment-processor"}') -eq 1 -and !(Firing 'PaymentObservationUnavailable') } 'scrape-loss alert resolved'
    Assert-Equal (((Invoke-Docker @('inspect','--format','{{.State.StartedAt}}',$processorId)) -join '').Trim()) $started 'no processor restart for alert recovery'
    Assert-Equal (OffsetTotal) $offset 'scrape failure cannot alter offsets'
    Snapshot 'scrape-restored'

    Compose @('stop','postgres') | Out-Null
    Submit 'TX-OBSERVE-QUEUED-1'
    Wait-For { (Value 'payments_consumer_listener_running') -eq 0 -and (Value 'payments_consumer_lag') -eq 1 } 'listener stopped with backlog'
    Submit 'TX-OBSERVE-QUEUED-2'
    Wait-For { (Value 'payments_consumer_lag') -eq 2 -and (Firing 'PaymentBacklog') } 'backlog alert firing'
    Assert-Equal (OffsetTotal) $offset 'failed work remains unacknowledged'
    Snapshot 'backlog'
    Compose @('start','postgres') | Out-Null
    Wait-For { (Sql 'SELECT count(*) FROM ledger_transactions') -eq '1' } 'database restored with unchanged ledger'
    Assert-Equal (Value 'payments_consumer_listener_running') 0 'database recovery alone cannot resume listener'
    Assert-Equal (OffsetTotal) $offset 'no premature ack after database recovery'
    Write-Output 'Manual recovery: explicitly restarting the processor after PostgreSQL restoration.'
    Compose @('restart','transaction-processor') | Out-Null
    Wait-For { (Value 'payments_consumer_listener_running') -eq 1 -and (Value 'payments_consumer_lag') -eq 0 `
        -and !(Firing 'PaymentBacklog') -and !(Firing 'PaymentObservationUnavailable') } 'manual drain and resolved alerts'
    Assert-Equal (Sql 'SELECT count(*) FROM ledger_transactions') '3' 'exactly one row per accepted transaction'
    Assert-Equal (OffsetTotal) ($offset + 2) 'queued offsets committed only after recovery'
    Wait-For { (Value 'sum(resets(payments_processing_attempts_total{job="payment-processor"}[10m]))') -ge 1 } 'counter reset observed'
    Snapshot 'recovered'
    'PASS: idle, allowlisted scrape, provisioned read-only dashboard, lost-scrape alert/recovery without restart, stopped-listener backlog, manual drain, offsets and counter reset.' |
        Tee-Object -FilePath (Join-Path $evidence 'result.txt') | Write-Output
} finally {
    try {
        if ($disconnected -and $prometheusId) { Invoke-Docker @('network','connect',"${project}_payments",$prometheusId) | Out-Null }
        Compose @('logs','--no-color') | Set-Content (Join-Path $evidence 'compose.log') -Encoding UTF8
    } finally {
        try {
            if (!$Keep) { Compose @('down','--volumes','--remove-orphans') | Write-Output }
            else { Write-Output "Kept isolated project $project. Endpoints and cleanup project name: $evidence" }
        } finally {
            foreach ($name in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnv[$name],'Process') }
        }
    }
}
