param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-zA-Z0-9.:-]+$')][string]$DeviceAddress,
    [string]$ApkPath,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.local\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw 'Run tools\build.ps1 -InstallSdk first.' }
if (-not $ApkPath) { $ApkPath = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk' }
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK not found: $ApkPath" }
$serial = if ($DeviceAddress.Contains(':')) { $DeviceAddress } else { "${DeviceAddress}:5555" }
& $adb connect $serial
$state = & $adb -s $serial get-state 2>&1
if ($LASTEXITCODE -ne 0 -or ($state -join '').Trim() -ne 'device') {
    throw 'TV is not authorized. Confirm the RSA debugging prompt on the TV, then run again.'
}
$api = & $adb -s $serial shell getprop ro.build.version.sdk
if ($LASTEXITCODE -ne 0 -or [int]($api -join '').Trim() -lt 23) { throw 'Android API 23 or newer is required.' }
& $adb -s $serial install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Existing app data has not been cleared.' }
if (-not $NoLaunch) {
    & $adb -s $serial shell am start -W -n cn.v65.live/.ui.OfficialWebActivity
    if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
}
