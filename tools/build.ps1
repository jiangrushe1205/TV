param([switch]$InstallSdk, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'bootstrap.ps1') -InstallSdk:$InstallSdk
$tasks = @(':app:assembleDebug', ':app:lintDebug')
if (-not $SkipTests) { $tasks += ':app:testDebugUnitTest' }
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & (Join-Path $gradleHome 'bin\gradle.bat') @gradleNetworkArgs @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
