$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runtime = Join-Path $project 'bundle\llama.cpp\windows-x64'
$model = Join-Path $project 'bundle\model\translategemma-4b.gguf'
$evidence = Join-Path $project 'reference\evidence'
New-Item -ItemType Directory $evidence -Force | Out-Null

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
$key = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$base = "http://127.0.0.1:$port"
$out = Join-Path $evidence 'llama-standalone.stdout.log'
$err = Join-Path $evidence 'llama-standalone.stderr.log'

$server = Start-Process -FilePath (Join-Path $runtime 'llama-server.exe') -WindowStyle Hidden -WorkingDirectory $runtime -PassThru `
    -RedirectStandardOutput $out -RedirectStandardError $err `
    -ArgumentList @('--model', $model, '--host', '127.0.0.1', '--port', "$port", '--ctx-size', '2048',
        '--api-key', $key, '--alias', 'translategemma', '--gpu-layers', '99', '--no-webui',
        '--no-jinja', '--chat-template', 'gemma')
try {
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        if ($server.HasExited) { throw "llama-server exited with $($server.ExitCode); see $err" }
        try {
            $health = Invoke-WebRequest "$base/health" -TimeoutSec 2 -SkipHttpErrorCheck
            if ($health.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "llama-server /health did not become ready; see $err" }
    Write-Output "HEALTH 200 on 127.0.0.1:$port"

    $headers = @{ Authorization = "Bearer $key" }
    $prompt = 'You are a professional English (en) to Japanese (ja) translator. Your goal is to accurately convey the meaning and nuances of the original English text while adhering to Japanese grammar, vocabulary, and cultural sensitivities.' + "`n" +
        'Produce only the Japanese translation, without any additional explanations or commentary. Preserve placeholder tokens like __LCT_0 and __LCT_1 exactly as written. Please translate the following English text into Japanese:' + "`n`n`n" +
        'You can find a shop in the village.'
    $payloads = @(
        @{ model = 'translategemma'; stream = $false; temperature = 0; max_tokens = 192; messages = @(@{ role = 'user'; content = $prompt }) },
        @{ model = 'translategemma'; stream = $false; temperature = 0; max_tokens = 192; messages = @(@{ role = 'user'; content = @(@{ type = 'text'; text = $prompt; source_lang_code = 'en'; target_lang_code = 'ja' }) }) }
    )
    $labels = @('string', 'typed')
    for ($i = 0; $i -lt $payloads.Count; $i++) {
        $body = ConvertTo-Json -InputObject $payloads[$i] -Depth 10 -Compress
        $response = Invoke-WebRequest "$base/v1/chat/completions" -Method Post -Headers $headers `
            -ContentType 'application/json' -Body $body -TimeoutSec 120 -SkipHttpErrorCheck
        $path = Join-Path $evidence "llama-standalone-$($labels[$i]).json"
        [System.IO.File]::WriteAllText($path, $response.Content)
        Write-Output "$($labels[$i]) status=$($response.StatusCode) response=$path"
        if ($response.StatusCode -eq 200) {
            $json = $response.Content | ConvertFrom-Json
            Write-Output "$($labels[$i]) finish=$($json.choices[0].finish_reason) text=$($json.choices[0].message.content)"
        }
    }
} finally {
    if (-not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
        $server.WaitForExit(5000) | Out-Null
    }
    Write-Output "llama-server stopped, pid=$($server.Id)"
}
