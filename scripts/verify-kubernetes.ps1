param([switch]$KeepCluster)

# Runs only against a new, uniquely named kind cluster with a private kubeconfig.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$kind = & (Join-Path $PSScriptRoot 'install-kind.ps1')
$kubectl = (Get-Command kubectl -ErrorAction Stop).Source
$helm = (Get-Command helm -ErrorAction Stop).Source
$cluster = 'payments-m3-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$context = "kind-$cluster"
$evidenceDirectory = Join-Path $repoRoot "artifacts/$cluster"
[IO.Directory]::CreateDirectory($evidenceDirectory) | Out-Null
$kubeconfig = Join-Path $evidenceDirectory 'kubeconfig'
$chart = Join-Path $repoRoot 'deploy/helm/payments'
$namespace = 'payments'
$release = 'payments'
$forwardProcess = $null
$created = $false

function Invoke-Tool([string]$File, [string[]]$Arguments, [string]$InputText) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($InputText) { $output = $InputText | & $File @Arguments 2>&1 }
        else { $output = & $File @Arguments 2>&1 }
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) { throw "Tool failed ($code): $File $($Arguments -join ' ')`n$($output -join "`n")" }
    return $output | ForEach-Object { "$_" }
}
function Kube([string[]]$Arguments, [string]$InputText) {
    Invoke-Tool $kubectl (@('--kubeconfig', $kubeconfig, '--context', $context, '--namespace', $namespace) + $Arguments) $InputText
}
function Helm([string[]]$Arguments) {
    Invoke-Tool $helm (@('--kubeconfig', $kubeconfig, '--kube-context', $context, '--namespace', $namespace) + $Arguments)
}
function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message : expected '$Expected', got '$Actual'" }
}
function Wait-For([scriptblock]$Check, [string]$Message) {
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    do {
        if (& $Check) { return }
        Start-Sleep -Milliseconds 750
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Message"
}
function Sql([string]$Statement) {
    (Kube @('exec', 'payments-postgres-0', '--', 'psql', '-U', 'payments', '-d', 'payments',
        '-v', 'ON_ERROR_STOP=1', '-Atc', $Statement)) -join "`n"
}
function Wait-Processor {
    Wait-For { (Kube @('logs', 'deployment/payments-transaction-processor', '-c', 'transaction-processor')) -match 'Started TransactionProcessorApplication' } 'processor startup'
}
function Start-Forward {
    if ($script:forwardProcess -and -not $script:forwardProcess.HasExited) { Stop-Process -Id $script:forwardProcess.Id }
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $script:forwardProcess = Start-Process -FilePath $kubectl -ArgumentList @('--kubeconfig', ('"' + $kubeconfig + '"'),
        '--context', $context, '-n', $namespace, 'port-forward', 'service/payments-transaction-api',
        "${port}:8080", '--address', '127.0.0.1') -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $evidenceDirectory 'port-forward.log') `
        -RedirectStandardError (Join-Path $evidenceDirectory 'port-forward-error.log')
    $script:baseUrl = "http://127.0.0.1:$port"
    Wait-For {
        try { (Invoke-WebRequest -UseBasicParsing "$script:baseUrl/actuator/health/readiness" -TimeoutSec 3).StatusCode -eq 200 }
        catch { $false }
    } 'API port-forward readiness'
}
function Submit([string]$Id, [decimal]$Amount, [string]$Currency = 'EUR') {
    $body = @{transactionId=$Id; correlationId="CORR-$Id"; accountId='ACC-K8S'; amount=$Amount; currency=$Currency; type='TRANSFER'} | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -UseBasicParsing "$script:baseUrl/api/v1/transactions" -Method Post `
        -ContentType 'application/json' -Headers @{'Idempotency-Key'=$Id} -Body $body -TimeoutSec 20
    Assert-Equal $response.StatusCode 202 'HTTP intake'
}
function Accepted([string]$Id) {
    Submit $Id 42
    Wait-For { (Sql "SELECT count(*) FROM ledger_transactions WHERE transaction_id='$Id'") -eq '1' } 'ledger insert'
}
function Marker {
    $deployment = (Kube @('get', 'deployment/payments-transaction-api', '-o', 'json')) -join "`n" | ConvertFrom-Json
    $deployment.spec.template.metadata.annotations.'payments.example/rollout-marker'
}

Write-Output "Isolated cluster: $cluster; kubeconfig: $kubeconfig"
try {
    Invoke-Tool $helm @('lint', $chart) | Write-Output
    $created = $true
    Invoke-Tool $kind @('create', 'cluster', '--name', $cluster, '--config', (Join-Path $repoRoot 'deploy/kind/config.yaml'),
        '--kubeconfig', $kubeconfig, '--wait', '180s') | Write-Output
    Kube @('create', 'namespace', $namespace) | Write-Output
    Kube @('label', 'namespace', $namespace, 'pod-security.kubernetes.io/enforce=restricted',
        'pod-security.kubernetes.io/enforce-version=v1.34') | Write-Output
    # Development-only credential is generated in memory and injected through stdin,
    # never written into Helm values/history or printed on the command line.
    $secret = @{apiVersion='v1'; kind='Secret'; metadata=@{name='payments-db'; namespace=$namespace};
        type='Opaque'; stringData=@{database='payments'; username='payments'; password=('dev-' + [guid]::NewGuid().ToString('N'))}} | ConvertTo-Json -Depth 6
    Kube @('create', '-f', '-') $secret | Write-Output
    $secret = $null
    $rendered = (Invoke-Tool $helm @('template', $release, $chart, '--namespace', $namespace)) -join "`n"
    Kube @('apply', '--dry-run=server', '-f', '-') $rendered | Write-Output
    Helm @('install', $release, $chart, '--wait', '--wait-for-jobs', '--timeout', '10m') | Write-Output
    Wait-Processor
    Start-Forward
    foreach ($component in @('api','processor')) {
        $deployment = (Kube @('get', "deployment/payments-transaction-$component", '-o', 'json')) -join "`n" | ConvertFrom-Json
        $spec = $deployment.spec.template.spec
        Assert-Equal $spec.automountServiceAccountToken $false 'no workload API token'
        Assert-Equal $spec.securityContext.runAsNonRoot $true 'non-root policy'
        Assert-Equal $spec.securityContext.seccompProfile.type 'RuntimeDefault' 'seccomp'
        $container = $spec.containers[0]
        Assert-Equal $container.securityContext.readOnlyRootFilesystem $true 'read-only application filesystem'
        Assert-Equal $container.securityContext.allowPrivilegeEscalation $false 'no privilege escalation'
        Assert-Equal ($container.securityContext.capabilities.drop -join ',') 'ALL' 'capabilities dropped'
        if (!$container.resources.requests.cpu -or !$container.resources.limits.memory) { throw 'Missing resource budget' }
        $publication = Get-Content (Join-Path $repoRoot 'docs/evidence/m2-publication.json') -Raw | ConvertFrom-Json
        $expected = $publication.images."transaction-$component"
        Assert-Equal $container.image (($expected.tag -split ':sha-')[0] + '@' + $expected.registryDigest) 'M2 image digest'
        Assert-Equal ((Kube @('exec', "deployment/payments-transaction-$component", '-c', "transaction-$component", '--', 'id', '-u')) -join '').Trim() '10001' 'runtime UID'
        if ($component -eq 'api' -and ($container.env.name -match 'POSTGRES|DATASOURCE')) { throw 'API received database configuration' }
    }
    Accepted 'TX-K8S-VALID'
    $original = Sql "SELECT row_to_json(t)::text FROM ledger_transactions t WHERE transaction_id='TX-K8S-VALID'"
    Submit 'TX-K8S-VALID' 42
    Wait-For { (Kube @('logs', 'deployment/payments-transaction-processor', '-c', 'transaction-processor')) -match 'transactionId=TX-K8S-VALID outcome=DUPLICATE' } 'duplicate processing'
    Assert-Equal (Sql 'SELECT count(*) FROM ledger_transactions') '1' 'one ledger row after duplicate'
    Submit 'TX-K8S-VALID' 43
    Submit 'TX-K8S-NEGATIVE' -1
    Submit 'TX-K8S-CURRENCY' 1 'USD'
    Wait-For { (Sql 'SELECT count(*) FROM transaction_rejections') -eq '3' } 'all rejection audits'
    Assert-Equal (Sql "SELECT row_to_json(t)::text FROM ledger_transactions t WHERE transaction_id='TX-K8S-VALID'") $original 'conflict cannot mutate ledger'
    Assert-Equal (Sql 'SELECT count(*) FROM ledger_transactions') '1' 'rejections never enter ledger'
    Assert-Equal (Sql "SELECT reason_codes::text FROM transaction_rejections WHERE transaction_id='TX-K8S-VALID'") '{PAYLOAD_CONFLICT}' 'conflict reason'
    Assert-Equal (Sql "SELECT reason_codes::text FROM transaction_rejections WHERE transaction_id='TX-K8S-NEGATIVE'") '{AMOUNT_NOT_POSITIVE}' 'amount reason'
    Assert-Equal (Sql "SELECT reason_codes::text FROM transaction_rejections WHERE transaction_id='TX-K8S-CURRENCY'") '{CURRENCY_NOT_EUR}' 'currency reason'

    Helm @('upgrade', $release, $chart, '--set-string', 'rolloutMarker=exercise', '--wait', '--wait-for-jobs', '--timeout', '5m') | Write-Output
    Assert-Equal (Marker) 'exercise' 'upgraded pod template'
    Wait-Processor
    Start-Forward
    Accepted 'TX-K8S-UPGRADED'
    Helm @('rollback', $release, '1', '--wait', '--wait-for-jobs', '--timeout', '5m') | Write-Output
    Assert-Equal (Marker) 'baseline' 'rolled-back pod template'
    Wait-Processor
    Start-Forward
    Accepted 'TX-K8S-ROLLBACK'

    # PVC survival across infrastructure pod recreation, followed by the documented
    # manual processor restart (technical failures stop its listener).
    Kube @('delete', 'pod', 'payments-postgres-0', 'payments-kafka-0', '--wait=true') | Write-Output
    Kube @('rollout', 'status', 'statefulset/payments-postgres', '--timeout=5m') | Write-Output
    Kube @('rollout', 'status', 'statefulset/payments-kafka', '--timeout=5m') | Write-Output
    Assert-Equal (Sql 'SELECT count(*) FROM ledger_transactions') '3' 'ledger survives infrastructure restart'
    Assert-Equal (Sql 'SELECT count(*) FROM transaction_rejections') '3' 'rejections survive infrastructure restart'
    Kube @('rollout', 'restart', 'deployment/payments-transaction-processor') | Write-Output
    Kube @('rollout', 'status', 'deployment/payments-transaction-processor', '--timeout=5m') | Write-Output
    Wait-Processor
    Accepted 'TX-K8S-RECOVERED'
    $records = Kube @('exec', 'payments-kafka-0', '--', 'env', 'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m',
        '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'localhost:9092', '--topic',
        'transactions.rejected', '--from-beginning', '--max-messages', '3', '--timeout-ms', '30000', '--property', 'print.key=true')
    foreach ($id in @('TX-K8S-VALID','TX-K8S-NEGATIVE','TX-K8S-CURRENCY')) {
        $record = @($records | Where-Object { $_.StartsWith("$id`t") })
        Assert-Equal $record.Count 1 'persisted rejection notification and key'
        $notification = ($record[0] -split "`t", 2)[1] | ConvertFrom-Json
        Assert-Equal $notification.correlationId "CORR-$id" 'notification correlation'
        Assert-Equal $notification.schemaVersion 1 'notification schema'
    }
    Helm @('history', $release, '-o', 'json') | Set-Content (Join-Path $evidenceDirectory 'helm-history.json')
    Kube @('get', 'pods', '-o', 'json') | Set-Content (Join-Path $evidenceDirectory 'pods.json')
    Write-Output 'PASS: digest-pinned images, restricted non-root pods, accepted/duplicate/conflict/rejected flows, upgrade, rollback, PVC persistence and manual recovery.'
} catch {
    try {
        Kube @('get', 'pods', '-o', 'wide') | Write-Output
        Kube @('get', 'events', '--sort-by=.lastTimestamp') | Write-Output
        foreach ($app in @('statefulset/payments-kafka','statefulset/payments-postgres','deployment/payments-transaction-api','deployment/payments-transaction-processor')) {
            Kube @('logs', $app, '--all-containers=true', '--tail=60') | Write-Output
        }
    } catch { Write-Warning 'Some diagnostic resources were unavailable' }
    throw
} finally {
    if ($forwardProcess -and -not $forwardProcess.HasExited) { Stop-Process -Id $forwardProcess.Id }
    if ($created -and !$KeepCluster) {
        Invoke-Tool $kind @('delete', 'cluster', '--name', $cluster, '--kubeconfig', $kubeconfig) | Write-Output
    } elseif ($created) {
        Write-Output "Retained cluster $cluster; delete with: $kind delete cluster --name $cluster --kubeconfig $kubeconfig"
    }
}
