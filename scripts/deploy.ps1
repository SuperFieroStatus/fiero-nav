<#
.SYNOPSIS
  Fiero Nav dev loop: build APK, install, grant location, launch, tail logcat.

.EXAMPLE
  .\scripts\deploy.ps1                 # build debug + install + launch (am start) + logcat
  .\scripts\deploy.ps1 -NoBuild        # reinstall last APK + launch + logcat
  .\scripts\deploy.ps1 -Release        # shrunk (R8) release build; installs over debug (same key)
  .\scripts\deploy.ps1 -Serial ABC123  # pick a device when several are attached

  Ctrl+C stops logcat. Works in Windows PowerShell 5.1 and PowerShell 7.
  Test a drive without moving (simulated GPS along the route):
    adb shell am start -n dev.mike.fieronav/.GuideActivity --es name Test --ef lat 30.2747 --ef lon -97.7404 --ez simulate true
#>
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$Clean,
    [switch]$NoLaunch,
    [switch]$NoLogcat,
    [switch]$Release,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Package = 'dev.mike.fieronav'
$Activity = "$Package/.MainActivity"
$Variant = if ($Release) { 'release' } else { 'debug' }
$Apk = Join-Path $Root "app\build\outputs\apk\$Variant\app-$Variant.apk"

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# ---- Android SDK location (local.properties, env, or default) ----
$SdkDir = $null
$localProps = Join-Path $Root 'local.properties'
if (Test-Path $localProps) {
    $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($line) { $SdkDir = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $SdkDir) { $SdkDir = $env:ANDROID_HOME }
if (-not $SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
if (-not $SdkDir) { $SdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

# ---- adb ----
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) { $Adb = $adbCmd.Source }
else {
    $Adb = Join-Path $SdkDir 'platform-tools\adb.exe'
    if (-not (Test-Path $Adb)) { Fail "adb not found on PATH or in $SdkDir\platform-tools" }
}
$AdbArgs = @()
if ($Serial) { $AdbArgs = @('-s', $Serial) }
function Adb { & $Adb @AdbArgs @args }

# ---- JDK for Gradle (needs 17+; the PATH java here may be Java 8) ----
function Ensure-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr'
    foreach ($c in $candidates) {
        $release = Join-Path $c 'release'
        if (Test-Path $release) {
            $ver = (Get-Content $release | Where-Object { $_ -match '^JAVA_VERSION=' }) -replace '^JAVA_VERSION="?([0-9]+).*', '$1'
            if ([int]$ver -ge 17) { $env:JAVA_HOME = $c; return }
        }
    }
    Fail 'No JDK 17+ found. Set JAVA_HOME or install Android Studio (bundled JBR).'
}

function Require-Device {
    # PS 5.1 turns redirected native stderr into errors; don't let 'Stop' throw on "no devices".
    $ErrorActionPreference = 'Continue'
    $state = (Adb get-state) 2>$null
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') {
        Adb devices
        Fail 'No authorised device. Plug in the Flip 2, enable USB debugging, accept the RSA prompt (or pass -Serial).'
    }
}


# ---- Build ----
if (-not $NoBuild) {
    Ensure-Jdk
    Step "Building $Variant APK (JAVA_HOME=$env:JAVA_HOME)"
    Push-Location $Root
    try {
        $tasks = @()
        if ($Clean) { $tasks += 'clean' }
        $tasks += if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
        # When this script's output is piped (e.g. run by a tool), a background Gradle daemon inherits
        # the pipe and the caller never sees EOF. Build without a daemon in that case.
        if ([Console]::IsOutputRedirected) { $tasks += '--no-daemon' }
        & (Join-Path $Root 'gradlew.bat') @tasks --console=plain
        if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    $sizeKb = [math]::Round((Get-Item $Apk).Length / 1KB)
    Step "APK: $Apk ($sizeKb KB)"
}
if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk (run without -NoBuild)" }

# ---- Install + launch ----
Require-Device
Step 'Installing'
Adb install -r $Apk
if ($LASTEXITCODE -ne 0) { Fail 'adb install failed' }

# Location permission without the on-screen prompt (the app also asks if it's missing).
Adb shell pm grant $Package android.permission.ACCESS_FINE_LOCATION

if (-not $NoLaunch) {
    Adb logcat -c
    Step "Launching $Activity"
    Adb shell am start -S -n $Activity
    if ($LASTEXITCODE -ne 0) { Fail 'am start failed' }
}

# ---- Observe ----
if (-not $NoLogcat) {
    Step 'logcat FieroNav / crashes (Ctrl+C to stop)'
    Adb logcat -v time -s FieroNav:V AndroidRuntime:E
}
