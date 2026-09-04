import test from "node:test";
import assert from "node:assert/strict";
import { CodexAppServer } from "../src/codex-app-server.mjs";

test("waitForTurn resolves the matching completed turn", async () => {
  const codex = new CodexAppServer();
  const waiting = codex.waitForTurn("thread-1", "turn-1", 1_000);
  codex.emit("notification", {
    method: "turn/completed",
    params: { threadId: "thread-1", turn: { id: "turn-1", status: "completed" } },
  });
  assert.equal((await waiting).id, "turn-1");
});

test("waitForTurn ignores completion from another thread", async () => {
  const codex = new CodexAppServer();
  const waiting = codex.waitForTurn("thread-1", "turn-1", 1_000);
  codex.emit("notification", {
    method: "turn/completed",
    params: { threadId: "thread-2", turn: { id: "turn-1", status: "completed" } },
  });
  codex.emit("notification", {
    method: "turn/completed",
    params: { threadId: "thread-1", turn: { id: "turn-1", status: "completed" } },
  });
  assert.equal((await waiting).status, "completed");
});

test("sendTextAndWait releases the thread after reading the completed response", async () => {
  const codex = new CodexAppServer();
  const calls = [];
  codex.recycle = async () => calls.push({ method: "app-server/recycle" });
  codex.request = async (method, params) => {
    calls.push({ method, params });
    if (method === "thread/resume") return { thread: { id: params.threadId } };
    if (method === "turn/start") {
      return { turn: { id: "turn-1", status: "completed" } };
    }
    if (method === "thread/read") return { thread: { id: params.threadId, turns: [] } };
    if (method === "thread/unsubscribe") return { status: "unsubscribed" };
    throw new Error(`Método inesperado: ${method}`);
  };

  const result = await codex.sendTextAndWait("thread-1", "hola");

  assert.equal(result.turn.status, "completed");
  assert.deepEqual(calls.map((call) => call.method), [
    "thread/resume",
    "turn/start",
    "thread/read",
    "thread/unsubscribe",
    "app-server/recycle",
  ]);
});

test("sendTextAndWait releases the thread when reading the response fails", async () => {
  const codex = new CodexAppServer();
  const calls = [];
  codex.recycle = async () => calls.push({ method: "app-server/recycle" });
  codex.request = async (method, params) => {
    calls.push({ method, params });
    if (method === "thread/resume") return { thread: { id: params.threadId } };
    if (method === "turn/start") {
      return { turn: { id: "turn-1", status: "completed" } };
    }
    if (method === "thread/read") throw new Error("lectura fallida");
    if (method === "thread/unsubscribe") return { status: "unsubscribed" };
    throw new Error(`Método inesperado: ${method}`);
  };

  await assert.rejects(codex.sendTextAndWait("thread-1", "hola"), /lectura fallida/);
  assert.deepEqual(calls.slice(-2).map((call) => call.method), [
    "thread/unsubscribe",
    "app-server/recycle",
  ]);
});

test("sendText releases the thread after asynchronous completion", async () => {
  const codex = new CodexAppServer();
  const calls = [];
  codex.recycle = async () => calls.push({ method: "app-server/recycle" });
  codex.request = async (method, params) => {
    calls.push({ method, params });
    if (method === "thread/resume") return { thread: { id: params.threadId } };
    if (method === "turn/start") {
      return { turn: { id: "turn-1", status: "completed" } };
    }
    if (method === "thread/unsubscribe") return { status: "unsubscribed" };
    throw new Error(`Método inesperado: ${method}`);
  };

  await codex.sendText("thread-1", "hola");
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(calls.slice(-2).map((call) => call.method), [
    "thread/unsubscribe",
    "app-server/recycle",
  ]);
});
