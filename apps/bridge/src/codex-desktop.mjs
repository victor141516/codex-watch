import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const LIST_CODEX_DESKTOP = String.raw`
$ErrorActionPreference = "Stop"
$processes = @(
  Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq "ChatGPT.exe" -and
    $_.ExecutablePath -match "\\WindowsApps\\OpenAI\.Codex_[^\\]+\\app\\ChatGPT\.exe$" -and
    $_.CommandLine -notmatch "(?:^|\s)--type="
  } | ForEach-Object {
    [pscustomobject]@{
      pid = [int]$_.ProcessId
      executablePath = [string]$_.ExecutablePath
    }
  }
)
ConvertTo-Json -InputObject $processes -Compress
`;

async function runPowerShell(script) {
  const { stdout } = await execFileAsync(
    "powershell.exe",
    ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
    { windowsHide: true, maxBuffer: 1024 * 1024 },
  );
  return stdout.trim();
}

function parseProcessList(output) {
  if (!output) return [];
  const parsed = JSON.parse(output);
  const values = Array.isArray(parsed) ? parsed : [parsed];
  return values.filter((value) => Number.isInteger(value?.pid) && value.pid > 0);
}

export async function listCodexDesktopProcesses({ run = runPowerShell } = {}) {
  if (process.platform !== "win32" && run === runPowerShell) return [];
  return parseProcessList(await run(LIST_CODEX_DESKTOP));
}

export async function isCodexDesktopOpen(options = {}) {
  return (await listCodexDesktopProcesses(options)).length > 0;
}

export async function closeCodexDesktop({ run = runPowerShell, gracePeriodMs = 4_000 } = {}) {
  const processes = await listCodexDesktopProcesses({ run });
  if (processes.length === 0) {
    return { wasOpen: false, closed: true, forced: false, processIds: [] };
  }

  const processIds = processes.map((item) => item.pid);
  const safeGracePeriodMs = Math.min(Math.max(Number(gracePeriodMs) || 4_000, 1_000), 30_000);
  const idList = processIds.join(",");
  const closeScript = String.raw`
$ErrorActionPreference = "Stop"
$ids = @(${idList})
$targets = @(Get-Process -Id $ids -ErrorAction SilentlyContinue)
$gracefulRequested = $false
foreach ($target in $targets) {
  if ($target.CloseMainWindow()) { $gracefulRequested = $true }
}
$deadline = [DateTime]::UtcNow.AddMilliseconds(${safeGracePeriodMs})
if ($gracefulRequested) {
  do {
    $remaining = @(Get-Process -Id $ids -ErrorAction SilentlyContinue)
    if ($remaining.Count -eq 0 -or [DateTime]::UtcNow -ge $deadline) { break }
    Start-Sleep -Milliseconds 200
  } while ($true)
} else {
  $remaining = @(Get-Process -Id $ids -ErrorAction SilentlyContinue)
}
$forcedIds = @($remaining | ForEach-Object { [int]$_.Id })
if ($forcedIds.Count -gt 0) {
  # El proceso puede terminar entre la comprobación y Stop-Process. Eso es éxito,
  # no un fallo del cierre, así que ignoramos únicamente esa carrera.
  Stop-Process -Id $forcedIds -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 250
}
$stillRunning = @(Get-Process -Id $ids -ErrorAction SilentlyContinue | ForEach-Object { [int]$_.Id })
[pscustomobject]@{
  closed = ($stillRunning.Count -eq 0)
  gracefulRequested = $gracefulRequested
  forced = ($forcedIds.Count -gt 0)
  forcedIds = $forcedIds
  stillRunning = $stillRunning
} | ConvertTo-Json -Compress
`;

  let result = {};
  let closeWarning = null;
  try {
    result = JSON.parse(await run(closeScript));
  } catch (error) {
    // La orden puede perder la carrera contra la propia salida de Codex. La
    // comprobación independiente de abajo decide el resultado real.
    closeWarning = error instanceof Error ? error.message : String(error);
  }

  let remainingProcesses;
  try {
    remainingProcesses = await listCodexDesktopProcesses({ run });
  } catch (error) {
    if (closeWarning) throw new Error(`${closeWarning}; además falló la verificación: ${error.message}`);
    throw error;
  }

  const closed = remainingProcesses.length === 0;
  return {
    wasOpen: true,
    closed,
    gracefulRequested: Boolean(result.gracefulRequested),
    forced: Boolean(result.forced),
    processIds,
    forcedIds: Array.isArray(result.forcedIds)
      ? result.forcedIds
      : result.forcedIds == null ? [] : [result.forcedIds],
    stillRunning: remainingProcesses.map((item) => item.pid),
    ...(closeWarning ? { warning: closeWarning } : {}),
  };
}
