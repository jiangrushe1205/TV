param([string]$Output = 'dist/living-room-live-v0.5.0-cache-source.zip')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$destination = [IO.Path]::GetFullPath((Join-Path $root $Output))
if (-not $destination.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be inside the project.'
}
New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$files = @(
    '.gitignore', 'build.gradle.kts', 'gradle.properties', 'settings.gradle.kts',
    'app/build.gradle.kts', 'app/proguard-rules.pro', 'LICENSE', 'README.md',
    'THIRD-PARTY.md', 'package.json', 'package-lock.json',
    'docs/CACHE-UPDATE.md'
)
$files += Get-ChildItem -LiteralPath (Join-Path $root 'app/src') -Recurse -File |
    ForEach-Object { $_.FullName.Substring($root.Length + 1) }
$files += Get-ChildItem -LiteralPath (Join-Path $root 'tools') -Recurse -File |
    Where-Object { $_.Extension -in @('.ps1', '.mjs', '.js', '.py', '.proto') -and $_.FullName -notmatch '[\\/]__pycache__[\\/]' } |
    ForEach-Object { $_.FullName.Substring($root.Length + 1) }
# Include only existing evidence; do not require deleted historical test outputs.
$evidence = Join-Path $root 'docs/evidence/v050/channels-final/report.json'
if (Test-Path -LiteralPath $evidence) { $files += 'docs/evidence/v050/channels-final/report.json' }
if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination }
$archive = [IO.Compression.ZipFile]::Open($destination, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($relative in $files) {
        $source = Join-Path $root $relative
        if (-not (Test-Path -LiteralPath $source)) { throw "Missing source file: $relative" }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, $source, $relative.Replace('\', '/'), [IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
} finally { $archive.Dispose() }
Write-Host "Source archive: $destination ($($files.Count) files)"
Write-Host "SHA-256: $((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant())"
