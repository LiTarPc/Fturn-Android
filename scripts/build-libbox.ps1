$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $projectRoot '.native'
$sourceRoot = Join-Path $nativeRoot 'sing-box-1.12.0'
New-Item -ItemType Directory -Force $nativeRoot | Out-Null
if (!(Test-Path $sourceRoot)) {
    $archive = Join-Path $nativeRoot 'sing-box.zip'
    Invoke-WebRequest 'https://github.com/SagerNet/sing-box/archive/refs/tags/v1.12.0.zip' -OutFile $archive
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne '5F0728F8C7054B4B03ABD50C28CC8A0F8B02D9EB18936A33C86A5DD16855227C') { throw 'sing-box source checksum mismatch' }
    Expand-Archive -LiteralPath $archive -DestinationPath $nativeRoot -Force
}
$env:GOPATH = Join-Path $nativeRoot 'go'
$env:GOCACHE = Join-Path $nativeRoot 'go-cache'
$env:GOTELEMETRY = 'off'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358'
# gomobile 0.1.7 cannot discover SDK platform names with minor versions (android-37.0).
# Use a workspace overlay for the Java compile stubs; the NDK target remains API 24.
if (!(Get-ChildItem (Join-Path $env:ANDROID_HOME 'platforms') -Directory | Where-Object Name -Match '^android-\d+$')) {
    $compilePlatform = Get-ChildItem (Join-Path $env:ANDROID_HOME 'platforms') -Directory |
        Where-Object Name -Match '^android-\d+\.\d+$' | Sort-Object Name -Descending | Select-Object -First 1
    if (!$compilePlatform) { throw 'Android SDK platform not found' }
    $overlayRoot = Join-Path $nativeRoot 'android-sdk'
    $overlayPlatform = Join-Path $overlayRoot ('platforms\' + $compilePlatform.Name.Split('.')[0])
    New-Item -ItemType Directory -Force $overlayPlatform | Out-Null
    Copy-Item -LiteralPath (Join-Path $compilePlatform.FullName 'android.jar') -Destination $overlayPlatform -Force
    $env:ANDROID_HOME = $overlayRoot
}
Push-Location $sourceRoot
try {
    & go mod edit '-require=github.com/klauspost/compress@v1.18.3'
    & go mod download github.com/klauspost/compress
    if ($LASTEXITCODE) { throw 'compress dependency verification failed' }
    & go install github.com/sagernet/gomobile/cmd/gomobile
    if ($LASTEXITCODE) { throw 'gomobile installation failed' }
    & go install github.com/sagernet/gomobile/cmd/gobind
    if ($LASTEXITCODE) { throw 'gobind installation failed' }
    $env:PATH = (Join-Path $env:GOPATH 'bin') + ';' + $env:PATH
    & gomobile bind -target android/arm64 -androidapi 24 -javapkg io.nekohasekai -libname box -trimpath -ldflags '-X github.com/sagernet/sing-box/constant.Version=1.12.0 -s -w' -tags 'with_gvisor,with_low_memory,with_clash_api,with_wireguard' ./experimental/libbox
    if ($LASTEXITCODE) { throw 'libbox build failed' }
    New-Item -ItemType Directory -Force (Join-Path $projectRoot 'app\libs') | Out-Null
    Copy-Item -LiteralPath 'libbox.aar' -Destination (Join-Path $projectRoot 'app\libs\libbox.aar') -Force
} finally { Pop-Location }
