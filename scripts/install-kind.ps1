$ErrorActionPreference = 'Stop'
$toolsDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/tools'
[IO.Directory]::CreateDirectory($toolsDirectory) | Out-Null
$kindPath = Join-Path $toolsDirectory 'kind.exe'
$expectedHash = '4b22adaa135368c5a465d56bbd8e520cbea87272a06ca00b6078e7b81515c9fc'
if (-not (Test-Path -LiteralPath $kindPath)) {
    Invoke-WebRequest -UseBasicParsing 'https://kind.sigs.k8s.io/dl/v0.33.0/kind-windows-amd64' -OutFile $kindPath
}
if ((Get-FileHash -LiteralPath $kindPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'kind binary checksum does not match the pinned v0.33.0 Windows amd64 release'
}
Write-Output $kindPath
