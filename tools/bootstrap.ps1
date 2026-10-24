param([switch]$InstallSdk)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $root '.local'
New-Item -ItemType Directory -Force -Path $local | Out-Null
$gradleNetworkArgs = @('-Dorg.gradle.internal.http.connectionTimeout=20000', '-Dorg.gradle.internal.http.socketTimeout=30000')
$targetUri = [uri]'https://dl.google.com'
$systemProxy = [System.Net.WebRequest]::GetSystemWebProxy().GetProxy($targetUri)
if ($systemProxy.Host -ne $targetUri.Host) {
    $gradleNetworkArgs += "-Dhttps.proxyHost=$($systemProxy.Host)", "-Dhttps.proxyPort=$($systemProxy.Port)", "-Dhttp.proxyHost=$($systemProxy.Host)", "-Dhttp.proxyPort=$($systemProxy.Port)"
}

$jdk = 'C:\Program Files\Java\jdk-21'
if (Test-Path (Join-Path $jdk 'bin\javac.exe')) { $env:JAVA_HOME = $jdk }
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { throw 'A JDK 17 or 21 is required. Set JAVA_HOME to the JDK directory.' }

$gradleHome = Join-Path $local 'gradle-8.11.1'
if (-not (Test-Path (Join-Path $gradleHome 'bin\gradle.bat'))) {
    $zip = Join-Path $local 'gradle-8.11.1-bin.zip'
    Write-Host 'Downloading Gradle 8.11.1 from the official distribution...'
    if (-not (Test-Path $zip) -or (Get-Item $zip).Length -ne 136920070) {
        try {
            Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' -OutFile $zip
        } catch {
            Write-Host 'Official download interrupted; using Huawei Cloud mirror with the official checksum.'
            Invoke-WebRequest -UseBasicParsing 'https://repo.huaweicloud.com/gradle/gradle-8.11.1-bin.zip' -OutFile $zip
        }
    }
    $checksumContent = (Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256').Content
    $expected = if ($checksumContent -is [byte[]]) { [Text.Encoding]::ASCII.GetString($checksumContent).Trim() } else { ([string]$checksumContent).Trim() }
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()) { throw 'Gradle SHA-256 mismatch' }
    Expand-Archive -LiteralPath $zip -DestinationPath $local -Force
}

$sdk = Join-Path $local 'android-sdk'
if ($InstallSdk) {
    $manager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path $manager)) {
        $zip = Join-Path $local 'commandlinetools-win-13114758_latest.zip'
        Write-Host 'Downloading Android command-line tools from Google...'
        Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile $zip
        $staging = Join-Path $local 'android-cli-extract'
        Expand-Archive -LiteralPath $zip -DestinationPath $staging -Force
        New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
        Move-Item -LiteralPath (Join-Path $staging 'cmdline-tools') -Destination (Join-Path $sdk 'cmdline-tools\latest')
    }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    1..20 | ForEach-Object { 'y' } | & $manager "--sdk_root=$sdk" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK licenses were not accepted' }
    & $manager "--sdk_root=$sdk" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed' }
}

if (Test-Path $sdk) {
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $escaped = $sdk.Replace('\', '/').Replace(':', '\:')
    "sdk.dir=$escaped" | Set-Content -LiteralPath (Join-Path $root 'local.properties') -Encoding ASCII
}
Write-Host "Gradle: $gradleHome"
Write-Host "Java: $env:JAVA_HOME"
Write-Host "SDK: $sdk"
