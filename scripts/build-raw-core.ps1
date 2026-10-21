param([string]$CoreRoot)
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'build-raw-core.py'))
if ($CoreRoot) { $arguments += @('--source', $CoreRoot) }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'RAW Android core build failed' }
