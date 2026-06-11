# Mock MasterAPI for emulator testing.
# Serves mock\openapi.json at /openapi.json and accepts any POST under /api/v1/*,
# logging each request to mock\requests.log. The emulator reaches it via http://10.0.2.2:8765.
param([int]$Port = 8765)

$root = $PSScriptRoot
$specPath = Join-Path $root "openapi.json"
$logPath = Join-Path $root "requests.log"

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Host "Mock MasterAPI listening on http://localhost:$Port/ (emulator: http://10.0.2.2:$Port/)"

while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $req = $ctx.Request
    $resp = $ctx.Response
    $bodyText = ""
    if ($req.HasEntityBody) {
        $reader = New-Object System.IO.StreamReader($req.InputStream, $req.ContentEncoding)
        $bodyText = $reader.ReadToEnd()
        $reader.Close()
    }
    $preview = if ($bodyText.Length -gt 500) { $bodyText.Substring(0, 500) + "...[truncated $($bodyText.Length) bytes total]" } else { $bodyText }
    $line = "$(Get-Date -Format o) $($req.HttpMethod) $($req.Url.PathAndQuery) ct=$($req.ContentType) len=$($req.ContentLength64)`n$preview`n---"
    Add-Content -Path $logPath -Value $line
    Write-Host $line

    # Simulated Authentik forwardAuth: browser-ish probes of /protected/spec get the
    # outpost redirect chain (start -> authorize?client_id=...), JSON fetches get the spec.
    if ($req.Url.AbsolutePath -eq "/protected/spec" -and $req.Headers["Accept"] -match "text/html") {
        $resp.StatusCode = 302
        $resp.RedirectLocation = "/outpost.goauthentik.io/start?rd=%2Fprotected%2Fspec"
        $resp.Close()
        continue
    }
    if ($req.Url.AbsolutePath -eq "/outpost.goauthentik.io/start") {
        $resp.StatusCode = 302
        $resp.RedirectLocation = "http://10.0.2.2:$Port/application/o/authorize/?client_id=mock-proxy-123&redirect_uri=callback&response_type=code"
        $resp.Close()
        continue
    }
    if ($req.Url.AbsolutePath -eq "/protected/spec") {
        $bytes = [System.IO.File]::ReadAllBytes((Join-Path $root "swagger2.json"))
        $resp.ContentType = "application/json"
        $resp.OutputStream.Write($bytes, 0, $bytes.Length)
        $resp.Close()
        continue
    }

    if ($req.Url.AbsolutePath -in @("/openapi.json", "/swagger2.json")) {
        $file = if ($req.Url.AbsolutePath -eq "/swagger2.json") { Join-Path $root "swagger2.json" } else { $specPath }
        $bytes = [System.IO.File]::ReadAllBytes($file)
        $resp.ContentType = "application/json"
        $resp.OutputStream.Write($bytes, 0, $bytes.Length)
    } else {
        $out = [System.Text.Encoding]::UTF8.GetBytes('{"status":"ok","mock":true,"path":"' + $req.Url.AbsolutePath + '"}')
        $resp.ContentType = "application/json"
        $resp.StatusCode = 200
        $resp.OutputStream.Write($out, 0, $out.Length)
    }
    $resp.Close()
}
