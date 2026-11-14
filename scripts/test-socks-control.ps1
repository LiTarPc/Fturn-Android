$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $projectRoot '.native'
$sourceRoot = Join-Path $nativeRoot 'sing-box-1.14.4'
$env:GOPATH = Join-Path $nativeRoot 'go'
$env:GOCACHE = Join-Path $nativeRoot 'go-cache'
$env:GOTELEMETRY = 'off'
$env:GOTOOLCHAIN = 'go1.26.10'
& python (Join-Path $PSScriptRoot 'patch-sing-socks.py') $sourceRoot
if ($LASTEXITCODE) { throw 'SOCKS UDP control lifecycle patch failed' }
Push-Location (Join-Path $sourceRoot 'fturn-sing')
try {
    & go test -mod=readonly ./protocol/socks -run '^TestFturnSocks' -count=3 -v -timeout=90s
    if ($LASTEXITCODE) { throw 'SOCKS UDP control lifecycle tests failed' }
} finally { Pop-Location }
