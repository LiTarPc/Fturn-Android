#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
native_root="$project_root/.native"
mkdir -p "$native_root"
if [ ! -d "$native_root/sing-box-1.12.0" ]; then
  curl -fL --retry 3 https://github.com/SagerNet/sing-box/archive/refs/tags/v1.12.0.zip -o "$native_root/sing-box.zip"
  echo "5f0728f8c7054b4b03abd50c28cc8a0f8b02d9eb18936a33c86a5dd16855227c  $native_root/sing-box.zip" | sha256sum -c -
  unzip -q "$native_root/sing-box.zip" -d "$native_root"
fi
export GOPATH="$native_root/go" GOCACHE="$native_root/go-cache" GOTELEMETRY=off
export PATH="$GOPATH/bin:$PATH"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
cd "$native_root/sing-box-1.12.0"
go mod edit -require=github.com/klauspost/compress@v1.18.3
go mod download github.com/klauspost/compress
go install github.com/sagernet/gomobile/cmd/gomobile
go install github.com/sagernet/gomobile/cmd/gobind
gomobile bind -target android/arm64 -androidapi 24 -javapkg io.nekohasekai -libname box -trimpath \
  -ldflags '-X github.com/sagernet/sing-box/constant.Version=1.12.0 -s -w' \
  -tags 'with_gvisor,with_low_memory,with_clash_api,with_wireguard' ./experimental/libbox
mkdir -p "$project_root/app/libs"
cp libbox.aar "$project_root/app/libs/libbox.aar"
