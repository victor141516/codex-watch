param(
    [string]$HostAddress = "0.0.0.0"
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$port = if ($env:CODEX_WATCH_PORT) { [int]$env:CODEX_WATCH_PORT } else { 8787 }
$env:CODEX_WATCH_HOST = $HostAddress
Set-Location -LiteralPath $projectDir

Write-Host "Codex Watch Bridge escuchará en ${HostAddress}:${port}."
Write-Host "Configura en Android una dirección de este ordenador accesible desde tu red privada."
Write-Host "Aviso: sin CODEX_WATCH_TOKEN, la API no requiere autenticación."
Write-Host "Pulsa Ctrl+C para detener el servidor."
node server.mjs
