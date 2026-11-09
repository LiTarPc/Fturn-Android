$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $projectRoot '.native'
$sourceRoot = Join-Path $nativeRoot 'sing-box-1.12.0'
$env:GOPATH = Join-Path $nativeRoot 'go'
$env:GOCACHE = Join-Path $nativeRoot 'go-cache'
$env:GOTELEMETRY = 'off'
& python (Join-Path $PSScriptRoot 'patch-sing-socks.py') $sourceRoot
if ($LASTEXITCODE) { throw 'SOCKS UDP control lifecycle patch failed' }
Push-Location (Join-Path $sourceRoot 'fturn-sing')
try {
    & go test -mod=readonly ./protocol/socks -run '^TestFturnSocks' -count=3 -v -timeout=90s
    if ($LASTEXITCODE) { throw 'SOCKS UDP control lifecycle tests failed' }
} finally { Pop-Location }
