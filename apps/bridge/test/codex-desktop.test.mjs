import test from "node:test";
import assert from "node:assert/strict";
import {
  closeCodexDesktop,
  isCodexDesktopOpen,
  listCodexDesktopProcesses,
} from "../src/codex-desktop.mjs";

test("detects the Codex Desktop root process", async () => {
  const run = async () => JSON.stringify([
    { pid: 1234, executablePath: "C:\\Program Files\\WindowsApps\\OpenAI.Codex_x64\\app\\ChatGPT.exe" },
  ]);

  assert.equal(await isCodexDesktopOpen({ run }), true);
  assert.equal((await listCodexDesktopProcesses({ run }))[0].pid, 1234);
});

test("reports Codex Desktop as closed when no root process exists", async () => {
  const run = async () => "[]";

  assert.deepEqual(await closeCodexDesktop({ run }), {
    wasOpen: false,
    closed: true,
    forced: false,
    processIds: [],
  });
});

test("reports whether graceful close needed a forced fallback", async () => {
  let call = 0;
  const run = async () => {
    call += 1;
    if (call === 1) return JSON.stringify([{ pid: 1234, executablePath: "Codex" }]);
    if (call === 2) return JSON.stringify({ closed: true, forced: true, forcedIds: 1234, stillRunning: [] });
    return "[]";
  };

  const result = await closeCodexDesktop({ run, gracePeriodMs: 1_000 });

  assert.equal(result.closed, true);
  assert.equal(result.forced, true);
  assert.deepEqual(result.processIds, [1234]);
  assert.deepEqual(result.forcedIds, [1234]);
});

test("treats a close-command race as success when Codex is actually gone", async () => {
  let call = 0;
  const run = async () => {
    call += 1;
    if (call === 1) return JSON.stringify([{ pid: 1234, executablePath: "Codex" }]);
    if (call === 2) throw new Error("Cannot find a process with id 1234");
    return "[]";
  };

  const result = await closeCodexDesktop({ run, gracePeriodMs: 1_000 });

  assert.equal(result.closed, true);
  assert.match(result.warning, /Cannot find a process/);
  assert.deepEqual(result.stillRunning, []);
});

test("reports failure only when Codex remains after the close attempt", async () => {
  let call = 0;
  const run = async () => {
    call += 1;
    if (call === 1 || call === 3) {
      return JSON.stringify([{ pid: 1234, executablePath: "Codex" }]);
    }
    return JSON.stringify({ closed: false, forced: true, forcedIds: [1234], stillRunning: [1234] });
  };

  const result = await closeCodexDesktop({ run, gracePeriodMs: 1_000 });

  assert.equal(result.closed, false);
  assert.deepEqual(result.stillRunning, [1234]);
});
