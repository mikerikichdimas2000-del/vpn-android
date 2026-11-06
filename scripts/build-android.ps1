param(
    [switch]$BuildApk,
    [switch]$SkipEngine
)

# Нативные команды (go, gradlew) пишут в stderr, поэтому не превращаем это в исключение:
# ошибки ловим по $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$engineRoot = Join-Path $androidRoot 'engine'
$workRoot = if ($env:VPN_BUILD_ROOT) { $env:VPN_BUILD_ROOT } else { Join-Path $env:LOCALAPPDATA 'vpn-build' }

# Где лежит портативный Go: сначала переменная окружения, потом наша папка сборки,
# потом обычная установка «для всех пользователей».
$goRoot = $env:VPN_GO_ROOT
if (-not $goRoot) {
    foreach ($candidate in @((Join-Path $workRoot 'go\go'), (Join-Path $workRoot 'go'))) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\go.exe')) { $goRoot = $candidate; break }
    }
}
if (-not $goRoot) {
    $goRoot = 'C:\Program Files\Go'
}
$goExe = Join-Path $goRoot 'bin\go.exe'
if (-not (Test-Path -LiteralPath $goExe)) {
    throw "Go не найден: $goExe. Скачай https://go.dev/dl/ (windows-amd64 zip) и распакуй в $workRoot\go."
}

$goPath = Join-Path $workRoot 'gopath'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath $sdkRoot)) {
    throw "Android SDK не найден: $sdkRoot"
}

$ndkRoot = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'ndk') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $ndkRoot) {
    throw "Android NDK не найден в $(Join-Path $sdkRoot 'ndk')"
}

$env:GOROOT = $goRoot
$env:GOPATH = $goPath
$env:GOMODCACHE = Join-Path $goPath 'pkg\mod'
$env:GOCACHE = Join-Path $workRoot 'gocache'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_NDK_HOME = $ndkRoot
$env:GOBIN = Join-Path $goPath 'bin'
$env:PATH = "$(Join-Path $goPath 'bin');$(Join-Path $goRoot 'bin');$env:PATH"

New-Item -ItemType Directory -Path (Join-Path $androidRoot 'app\libs') -Force | Out-Null

if (-not $SkipEngine) {
    Push-Location $engineRoot
    try {
        $mobileVersion = (& $goExe list -m -f '{{.Version}}' golang.org/x/mobile).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $mobileVersion) {
            throw 'В engine\go.mod не закреплён инструмент Go Mobile.'
        }

        if (-not (Test-Path -LiteralPath (Join-Path $env:GOBIN 'gomobile.exe'))) {
            Write-Output "Устанавливаю gomobile@$mobileVersion ..."
            & $goExe install "golang.org/x/mobile/cmd/gomobile@$mobileVersion"
            if ($LASTEXITCODE -ne 0) { throw 'Не удалось установить gomobile в локальный GOPATH.' }
        }

        Write-Output 'gomobile init ...'
        & (Join-Path $env:GOBIN 'gomobile.exe') init
        if ($LASTEXITCODE -ne 0) { throw 'Не удалось подготовить gomobile для Android NDK.' }

        $aar = Join-Path $androidRoot 'app\libs\engine.aar'
        Write-Output 'Собираю engine.aar (arm, arm64, x86_64) ...'
        & (Join-Path $env:GOBIN 'gomobile.exe') bind '-target=android/arm,android/arm64,android/amd64' '-androidapi=23' '-javapkg=com.dryakhlitsyn.vpn' '-ldflags=-s -w' '-trimpath' -o $aar .
        if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать Android-библиотеку AmneziaWG.' }
        Write-Output "Готово: $aar"
    }
    finally {
        Pop-Location
    }
}

if ($BuildApk) {
    $wrapper = Join-Path $androidRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) {
        throw "Gradle Wrapper не найден: $wrapper. Открой папку проекта в Android Studio и создай Wrapper."
    }
    & $wrapper -p $androidRoot assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать APK.' }
    Write-Output (Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk')
}
else {
    Write-Output "Библиотека движка собрана. Теперь: .\scripts\build-android.ps1 -BuildApk"
}
