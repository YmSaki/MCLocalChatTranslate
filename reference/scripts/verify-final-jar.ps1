$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$version = ((Get-Content (Join-Path $project 'gradle.properties')) -match '^version=' -replace '^version=', '')
$jarPath = Join-Path $project "build\libs\localchattranslate-$version.jar"
$archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    foreach ($name in @('fabric.mod.json', 'localchattranslate.mixins.json',
            'local/chattranslate/TranslationClient.class',
            'local/chattranslate/runtime/LlamaCppRuntime.class',
            'local/chattranslate/runtime/WindowsJob.class',
            'bundled/licenses/NOTICE', 'bundled/licenses/gemma-terms.html',
            'bundled/licenses/gemma-prohibited-use-policy.html',
            'bundled/licenses/llama.cpp-LICENSE',
            'bundled/llama.cpp/windows-x64/LICENSE-LLVM-OpenMP')) {
        if (-not $archive.GetEntry($name)) { throw "Missing JAR entry: $name" }
    }
    $manifestEntry = $archive.GetEntry('bundled/manifest.json')
    if (-not $manifestEntry) { throw 'Missing bundled manifest' }
    $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($archive.Entries | Where-Object { $_.FullName -like '*.gguf' }) { throw 'JAR must not contain a GGUF model' }
    if (-not $manifest.model -or $manifest.model.url -notlike 'https://*' -or $manifest.model.sha256 -notmatch '^[0-9a-f]{64}$') {
        throw 'Manifest lacks a valid pinned model description'
    }
    $count = 0
    foreach ($file in $manifest.files) {
        $entry = $archive.GetEntry($file.resource)
        if (-not $entry) { throw "Missing JAR asset: $($file.resource)" }
        if ($entry.Length -ne $file.size) { throw "Size mismatch: $($file.resource)" }
        $stream = $entry.Open()
        try { $actual = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() }
        finally { $stream.Dispose() }
        if ($actual -ne $file.sha256) { throw "SHA-256 mismatch: $($file.resource)" }
        $count++
    }
    Write-Output "Verified $count native files in JAR; bundle version $($manifest.bundleVersion); model $($manifest.model.file)"
} finally {
    $archive.Dispose()
}
$artifact = Get-Item $jarPath
$hash = (Get-FileHash $jarPath -Algorithm SHA256).Hash
if ($artifact.Length -ge 2GB) { throw "JAR is too large for CurseForge or GitHub Releases: $($artifact.Length) bytes" }
Write-Output "JAR bytes: $($artifact.Length)"
Write-Output "JAR SHA-256: $hash"
