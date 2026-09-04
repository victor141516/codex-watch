param(
    [string]$HostAddress = "0.0.0.0"
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$port = if ($env:CODEX_WATCH_PORT) { [int]$env:CODEX_WATCH_PORT } else { 8787 }
$healthUrl = "http://127.0.0.1:${port}/api/health"
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($health.ok) {
            Write-Host "Codex Watch Bridge ya está activo en el puerto $port."
            return
        }
    } catch {
        throw "El puerto $port ya está ocupado por otro proceso."
    }
}

$foregroundScript = Join-Path $projectDir "start-network.ps1"
$command = "& '" + $foregroundScript.Replace("'", "''") + "' -HostAddress '" + $HostAddress.Replace("'", "''") + "'"
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand $encodedCommand"
}
if ($created.ReturnValue -ne 0) {
    throw "Windows no pudo iniciar el bridge en segundo plano (código $($created.ReturnValue))."
}

$deadline = [DateTime]::UtcNow.AddSeconds(15)
do {
    Start-Sleep -Milliseconds 300
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        if ($health.ok) {
            Write-Host "Codex Watch Bridge iniciado en segundo plano en el puerto $port."
            return
        }
    } catch {
        # App Server puede tardar unos segundos en inicializarse.
    }
} while ([DateTime]::UtcNow -lt $deadline)

throw "El bridge se inició, pero no respondió dentro de 15 segundos."
