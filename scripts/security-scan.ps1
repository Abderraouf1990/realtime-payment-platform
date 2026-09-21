param(
    [string]$Image,
    [string]$OutputDirectory = 'artifacts/security',
    [switch]$SourceOnly
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$scanner = (Get-Content (Join-Path $repoRoot '.github/security/trivy-image.txt') -Raw).Trim()
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutputDirectory)) | Out-Null
$output = [IO.Path]::GetFullPath($OutputDirectory)
$mounts = @('--rm', '--mount', "type=bind,source=$output,target=/reports",
    '--mount', 'type=volume,source=payments-trivy-cache,target=/root/.cache/trivy')

function Invoke-Scanner([string[]]$ScannerArgs, [string[]]$ExtraMounts = @()) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker run @mounts @ExtraMounts $scanner @ScannerArgs 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) { throw 'Trivy failed: missing database/network/scanner errors are blocking' }
}

if ($SourceOnly) {
    Invoke-Scanner @('fs', '--scanners', 'secret', '--offline-scan', '--format', 'json', '--output', '/reports/.raw-secrets.json',
        '--skip-dirs', '.git', '--skip-dirs', 'artifacts', '--skip-dirs', '**/target', '/source') `
        @('--mount', "type=bind,source=$repoRoot,target=/source,readonly")
    & python (Join-Path $PSScriptRoot 'security_gate.py') trivy (Join-Path $output '.raw-secrets.json') `
        --sanitized-output (Join-Path $output 'secrets.json')
    $gateCode = $LASTEXITCODE
} else {
    if (!$Image) { throw 'Image is required without -SourceOnly' }
    # Scan an exported image, not a mounted Docker socket. The same archive is promoted.
    & docker image save --output (Join-Path $output 'image.tar') $Image
    if ($LASTEXITCODE -ne 0) { throw 'Image export failed' }
    Invoke-Scanner @('image', '--input', '/reports/image.tar', '--scanners', 'vuln,secret',
        '--format', 'json', '--output', '/reports/.raw-trivy.json')
    & python (Join-Path $PSScriptRoot 'security_gate.py') trivy (Join-Path $output '.raw-trivy.json') `
        --sanitized-output (Join-Path $output 'trivy.json')
    $gateCode = $LASTEXITCODE
    Invoke-Scanner @('image', '--input', '/reports/image.tar', '--format', 'cyclonedx',
        '--output', '/reports/sbom.cdx.json')
}
if ($gateCode -ne 0) { throw 'Security policy failed; inspect the redacted reports' }
