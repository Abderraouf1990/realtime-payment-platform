# Isolated acceptance exercise: only this script's project and volumes are removed.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$project = 'payments-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$composeArgs = @('--project-directory', $repoRoot, '--file', (Join-Path $repoRoot 'docker-compose.yml'),
    '--env-file', (Join-Path $repoRoot '.env.example'), '--project-name', $project)
$testEnv = @{
    API_PORT = '0'; KAFKA_PORT = '0'; POSTGRES_PORT = '0'
    POSTGRES_DB = 'payments'; POSTGRES_USER = 'payments'; POSTGRES_PASSWORD = 'payments_smoke_only'
    KAFKA_CLUSTER_ID = 'MkU3OEVBNTcwNTJENDM2Qk'; KAFKA_CONSUMER_GROUP = 'compose-smoke'
    TRANSACTIONS_RECEIVED_TOPIC = 'transactions.received'; TRANSACTIONS_REJECTED_TOPIC = 'transactions.rejected'
}
$savedEnv = @{}

function Invoke-Compose([string[]]$CommandArgs) {
    # Windows PowerShell treats redirected native stderr as ErrorRecords, even for progress.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker compose @composeArgs @CommandArgs 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) { throw "docker compose $($CommandArgs -join ' ') failed ($code):`n$($output -join "`n")" }
    return $output | ForEach-Object { "$_" }
}

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message : expected '$Expected', got '$Actual'" }
}

function Read-Sql([string]$Sql) {
    return (Invoke-Compose @('exec', '-T', 'postgres', 'psql', '-U', 'payments', '-d', 'payments',
        '-v', 'ON_ERROR_STOP=1', '-Atc', $Sql)) -join "`n"
}

function Wait-For([scriptblock]$Check, [string]$Message) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        if (& $Check) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Message"
}

function Submit([string]$Id, [decimal]$Amount, [string]$Currency = 'EUR') {
    $body = @{ transactionId=$Id; correlationId="CORR-$Id"; accountId='ACC-SMOKE';
        amount=$Amount; currency=$Currency; type='TRANSFER' } | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/api/v1/transactions" -Method Post `
        -ContentType 'application/json' -Headers @{ 'Idempotency-Key'=$Id } -Body $body -TimeoutSec 20
    Assert-Equal $response.StatusCode 202 'HTTP intake'
}

try {
    foreach ($name in $testEnv.Keys) {
        $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $testEnv[$name], 'Process')
    }
    Invoke-Compose @('config', '--quiet') | Out-Null
    Invoke-Compose @('build') | Write-Output
    # --wait checks API/infra health; processor running alone is not proof of listener health.
    Invoke-Compose @('up', '-d', '--wait', '--wait-timeout', '180') | Write-Output
    $binding = (Invoke-Compose @('port', 'transaction-api', '8080') | Select-Object -Last 1).Trim()
    $baseUrl = "http://$binding"
    Wait-For { (Invoke-Compose @('logs', '--no-color', 'transaction-processor')) -match 'Started TransactionProcessorApplication' } 'processor startup and migrations'
    foreach ($service in @('transaction-api', 'transaction-processor')) {
        Assert-Equal ((Invoke-Compose @('exec', '-T', $service, 'id', '-u')) -join '').Trim() '10001' "$service UID"
    }
    $apiEnv = Invoke-Compose @('exec', '-T', 'transaction-api', 'printenv')
    if ($apiEnv -match '^(POSTGRES_|SPRING_DATASOURCE_)') { throw 'API received database configuration' }

    Submit 'TX-COMPOSE-VALID' 42.00
    Wait-For { (Read-Sql "SELECT count(*) FROM ledger_transactions WHERE transaction_id='TX-COMPOSE-VALID'") -eq '1' } 'accepted ledger entry'
    $original = Read-Sql "SELECT row_to_json(t)::text FROM ledger_transactions t WHERE transaction_id='TX-COMPOSE-VALID'"
    Submit 'TX-COMPOSE-VALID' 42.00
    # Wait for actual duplicate processing; a count immediately after POST could pass too early.
    Wait-For { (Invoke-Compose @('logs', '--no-color', 'transaction-processor')) -match 'transactionId=TX-COMPOSE-VALID outcome=DUPLICATE' } 'duplicate processing'
    Assert-Equal (Read-Sql "SELECT row_to_json(t)::text FROM ledger_transactions t WHERE transaction_id='TX-COMPOSE-VALID'") $original 'immutable duplicate ledger'
    Assert-Equal (Read-Sql 'SELECT count(*) FROM transaction_rejections') '0' 'accepted payments have no audit rejection'

    Submit 'TX-COMPOSE-REJECTED' -1.00
    Wait-For { (Read-Sql "SELECT count(*) FROM transaction_rejections WHERE transaction_id='TX-COMPOSE-REJECTED'") -eq '1' } 'durable rejection'
    Assert-Equal (Read-Sql "SELECT reason_codes::text FROM transaction_rejections WHERE transaction_id='TX-COMPOSE-REJECTED'") '{AMOUNT_NOT_POSITIVE}' 'rejection reason'
    Assert-Equal (Read-Sql "SELECT correlation_id FROM transaction_rejections WHERE transaction_id='TX-COMPOSE-REJECTED'") 'CORR-TX-COMPOSE-REJECTED' 'audit correlation'
    Assert-Equal (Read-Sql "SELECT count(*) FROM ledger_transactions WHERE transaction_id='TX-COMPOSE-REJECTED'") '0' 'rejection absent from ledger'
    $records = Invoke-Compose @('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server', 'kafka:29092', '--topic', 'transactions.rejected', '--from-beginning',
        '--max-messages', '1', '--timeout-ms', '30000', '--property', 'print.key=true')
    $record = @($records | Where-Object { $_.StartsWith("TX-COMPOSE-REJECTED`t") })
    Assert-Equal $record.Count 1 'rejection notification and Kafka key'
    $notification = ($record[0] -split "`t", 2)[1] | ConvertFrom-Json
    Assert-Equal $notification.schemaVersion 1 'notification version'
    Assert-Equal $notification.transactionId 'TX-COMPOSE-REJECTED' 'notification identity'
    Assert-Equal $notification.correlationId 'CORR-TX-COMPOSE-REJECTED' 'notification correlation'
    Assert-Equal ($notification.reasonCodes -join ',') 'AMOUNT_NOT_POSITIVE' 'notification reason'
    if (!$notification.rejectedAt) { throw 'Missing rejection timestamp' }

    # Keep named volumes across a real down/up, then verify persisted data and consumer recovery.
    Invoke-Compose @('down') | Write-Output
    Invoke-Compose @('up', '-d', '--wait', '--wait-timeout', '180') | Write-Output
    $binding = (Invoke-Compose @('port', 'transaction-api', '8080') | Select-Object -Last 1).Trim()
    $baseUrl = "http://$binding"
    Assert-Equal (Read-Sql 'SELECT count(*) FROM ledger_transactions') '1' 'ledger survives restart'
    Assert-Equal (Read-Sql 'SELECT count(*) FROM transaction_rejections') '1' 'audit survives restart'
    Submit 'TX-COMPOSE-AFTER-RESTART' 12.00
    Wait-For { (Read-Sql "SELECT count(*) FROM ledger_transactions WHERE transaction_id='TX-COMPOSE-AFTER-RESTART'") -eq '1' } 'processing after restart'
    Write-Output 'PASS: non-root, HTTP acceptance, idempotent retry, durable rejection, Kafka notification, volumes and restart.'
} catch {
    try { Invoke-Compose @('logs', '--no-color', '--tail', '150') | Write-Output } catch { Write-Warning $_ }
    throw
} finally {
    try {
        Invoke-Compose @('down', '--volumes', '--remove-orphans') | Write-Output
    } finally {
        foreach ($name in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process') }
    }
}
