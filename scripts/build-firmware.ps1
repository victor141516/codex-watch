param(
    [switch]$Diagnostic
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$firmware = Join-Path $root "firmware\esp32"
$diagnosticValue = if ($Diagnostic) { "1" } else { "0" }
docker run --rm -v "${firmware}:/project" -w /project espressif/idf:v5.5.5 `
    idf.py -D CODEX_WATCH_DIAGNOSTIC=$diagnosticValue build
if ($LASTEXITCODE -ne 0) {
    throw "La compilacion del firmware ha fallado."
}

$artifacts = Join-Path $root "artifacts"
New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
$image = Join-Path $artifacts "codex-watch-esp32-merged.bin"
python -m esptool --chip esp32s3 merge-bin `
    -o $image `
    --flash-mode dio `
    --flash-size 16MB `
    --flash-freq 80m `
    0x0 (Join-Path $firmware "build\bootloader\bootloader.bin") `
    0x8000 (Join-Path $firmware "build\partition_table\partition-table.bin") `
    0x10000 (Join-Path $firmware "build\codex_watch_ble.bin")
if ($LASTEXITCODE -ne 0) {
    throw "No se pudo generar la imagen combinada del firmware."
}
