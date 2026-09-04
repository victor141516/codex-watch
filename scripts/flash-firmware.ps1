param(
    [Parameter(Mandatory = $true)]
    [string]$Port
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$image = Join-Path $root "artifacts\codex-watch-esp32-merged.bin"
if (-not (Test-Path -LiteralPath $image)) {
    throw "No se encuentra $image. Compila y genera primero la imagen combinada."
}

python -m esptool --chip esp32s3 --port $Port --baud 921600 write-flash 0x0 $image
if ($LASTEXITCODE -ne 0) {
    throw "No se pudo flashear el firmware en $Port."
}
