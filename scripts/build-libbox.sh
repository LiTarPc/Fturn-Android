#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
native_root="$project_root/.native"
mkdir -p "$native_root"
if [ ! -d "$native_root/sing-box-1.14.4" ]; then
  curl -fL --retry 3 https://codeload.github.com/SagerNet/sing-box/zip/refs/tags/v1.14.4 -o "$native_root/sing-box.zip"
  echo "9037718f85b67a004517c6a9190b98c68483de643f57c62eb6c889e01beae4bc  $native_root/sing-box.zip" | sha256sum -c -
  unzip -q "$native_root/sing-box.zip" -d "$native_root"
fi
python3 "$project_root/scripts/patch-libbox.py" "$native_root/sing-box-1.14.4"
export GOPATH="$native_root/go" GOCACHE="$native_root/go-cache" GOTELEMETRY=off GOTOOLCHAIN=go1.26.10
export PATH="$GOPATH/bin:$PATH"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
# gomobile 0.1.7 only recognizes integer SDK folder names. Keep API 24 native
# targeting and provide Android 37.0 Java stubs through a workspace overlay.
if ! find "$ANDROID_HOME/platforms" -mindepth 1 -maxdepth 1 -type d | grep -Eq '/android-[0-9]+$'; then
  compile_platform="$(find "$ANDROID_HOME/platforms" -mindepth 1 -maxdepth 1 -type d -name 'android-*.*' | sort -V | tail -1)"
  test -n "$compile_platform"
  platform_name="$(basename "$compile_platform")"
  overlay_root="$native_root/android-sdk"
  overlay_platform="$overlay_root/platforms/${platform_name%%.*}"
  mkdir -p "$overlay_platform"
  cp "$compile_platform/android.jar" "$overlay_platform/android.jar"
  export ANDROID_HOME="$overlay_root"
fi
cd "$native_root/sing-box-1.14.4"
python3 "$project_root/scripts/patch-sing-socks.py" "$PWD"
python3 "$project_root/scripts/patch-fou.py" "$PWD"
go install github.com/sagernet/gomobile/cmd/gomobile
go install github.com/sagernet/gomobile/cmd/gobind
gomobile bind -target android/arm64 -androidapi 24 -javapkg io.nekohasekai -libname box -trimpath \
  -ldflags '-checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=1.14.4 -s -w' \
  -tags 'with_gvisor,with_low_memory,with_clash_api,with_wireguard,with_utls,with_grpc' ./experimental/libbox
mkdir -p "$project_root/app/libs"
cp libbox.aar "$project_root/app/libs/libbox.aar"
