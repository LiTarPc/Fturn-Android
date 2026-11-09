#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
native_root="$project_root/.native"
source_root="$native_root/sing-box-1.12.0"
export GOPATH="$native_root/go" GOCACHE="$native_root/go-cache" GOTELEMETRY=off
python3 "$project_root/scripts/patch-sing-socks.py" "$source_root"
cd "$source_root/fturn-sing"
go test -mod=readonly -race ./protocol/socks -run '^TestFturnSocks' -count=3 -v -timeout=90s
