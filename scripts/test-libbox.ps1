$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $projectRoot '.native'
$sourceRoot = Join-Path $nativeRoot 'sing-box-1.14.4'
$env:GOPATH = Join-Path $nativeRoot 'go'
$env:GOCACHE = Join-Path $nativeRoot 'go-cache'
$env:GOTELEMETRY = 'off'
$env:GOTOOLCHAIN = 'go1.26.10'
$env:FTURN_VLESS_FIXTURES = Join-Path $projectRoot 'app/build/vless-fixtures'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'tests/vless_relay_test.go') -Destination (Join-Path $sourceRoot 'experimental/libbox/fturn_vless_relay_test.go') -Force
Push-Location $sourceRoot
try {
    & go test -v -tags 'with_gvisor,with_wireguard' ./protocol/fturnfou -count=20 -timeout=120s
    if ($LASTEXITCODE) { throw 'FOU transport tests failed' }
    & go test -v -tags 'with_gvisor,with_low_memory,with_clash_api,with_wireguard,with_utls,with_grpc' ./experimental/libbox -run '^TestFturnVlessRelay$' -timeout 120s -count=1
    if ($LASTEXITCODE) { throw 'Native VLESS relay tests failed' }
} finally { Pop-Location }
