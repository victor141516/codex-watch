$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Push-Location (Join-Path $root "apps\android")
try {
    .\gradlew.bat :app:lintDebug :app:assembleDebug
} finally {
    Pop-Location
}
