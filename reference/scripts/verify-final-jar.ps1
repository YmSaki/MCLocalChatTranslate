$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jarPath = Join-Path $project 'build\libs\localchattranslate-0.4.0+26.1.2.jar'
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
    Write-Output "Verified $count native/model files in Zip64 JAR; bundle version $($manifest.bundleVersion)"
} finally {
    $archive.Dispose()
}
$artifact = Get-Item $jarPath
$hash = (Get-FileHash $jarPath -Algorithm SHA256).Hash
Write-Output "JAR bytes: $($artifact.Length)"
Write-Output "JAR SHA-256: $hash"
