#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
native_root="$project_root/.native"
export GOPATH="$native_root/go" GOCACHE="$native_root/go-cache" GOTELEMETRY=off
export FTURN_VLESS_FIXTURES="$project_root/app/build/vless-fixtures"
cp "$project_root/scripts/tests/vless_relay_test.go" "$native_root/sing-box-1.12.0/experimental/libbox/fturn_vless_relay_test.go"
cd "$native_root/sing-box-1.12.0"
go test -v -tags 'with_gvisor,with_low_memory,with_clash_api,with_wireguard,with_utls,with_grpc' ./experimental/libbox -run '^TestFturnVlessRelay$' -timeout 120s -count=1
