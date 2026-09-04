import { EventEmitter } from "node:events";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { access, readdir, stat } from "node:fs/promises";
import path from "node:path";

const ALL_THREAD_SOURCES = [
  "cli",
  "vscode",
  "exec",
  "appServer",
  "subAgent",
  "subAgentReview",
  "subAgentCompact",
  "subAgentThreadSpawn",
  "subAgentOther",
  "unknown",
];

async function isFile(candidate) {
  try {
    await access(candidate);
    return (await stat(candidate)).isFile();
  } catch {
    return false;
  }
}

export async function findCodexBinary(env = process.env) {
  if (env.CODEX_BIN) {
    if (!(await isFile(env.CODEX_BIN))) {
      throw new Error(`CODEX_BIN no apunta a un archivo: ${env.CODEX_BIN}`);
    }
    return env.CODEX_BIN;
  }

  if (process.platform === "win32" && env.LOCALAPPDATA) {
    const binRoot = path.join(env.LOCALAPPDATA, "OpenAI", "Codex", "bin");
    try {
      const dirs = await readdir(binRoot, { withFileTypes: true });
      const candidates = [];
      for (const dir of dirs) {
        if (!dir.isDirectory()) continue;
        const candidate = path.join(binRoot, dir.name, "codex.exe");
        if (await isFile(candidate)) {
          candidates.push({ candidate, mtimeMs: (await stat(candidate)).mtimeMs });
        }
      }
      candidates.sort((a, b) => b.mtimeMs - a.mtimeMs);
      if (candidates[0]) return candidates[0].candidate;
    } catch {
      // Fall through to PATH.
    }
  }

  return process.platform === "win32" ? "codex.exe" : "codex";
}

export class CodexAppServer extends EventEmitter {
  constructor({ binary, requestTimeoutMs = 30_000 } = {}) {
    super();
    this.binary = binary;
    this.requestTimeoutMs = requestTimeoutMs;
    this.child = null;
    this.nextId = 1;
    this.pending = new Map();
    this.stderrTail = [];
    this.completedTurns = new Map();
    this.startPromise = null;
    this.recyclePromise = null;
    this.activeTurns = 0;
  }

  async start() {
    if (this.child) return;
    if (this.startPromise) return this.startPromise;
    this.startPromise = this.#startChild();
    try {
      await this.startPromise;
    } finally {
      this.startPromise = null;
    }
  }

  async #startChild() {
    const binary = this.binary ?? (await findCodexBinary());
    this.binary = binary;
    const child = spawn(binary, ["app-server", "--stdio"], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    this.child = child;

    child.once("error", (error) => {
      if (this.child === child) this.#failAll(error);
    });
    child.once("exit", (code, signal) => {
      const detail = this.stderrTail.at(-1) ?? "sin detalle";
      const error = new Error(
        `Codex App Server terminó (código ${code}, señal ${signal ?? "ninguna"}): ${detail}`,
      );
      if (this.child === child) {
        this.child = null;
        this.#failAll(error);
        this.emit("exit", error);
      }
    });

    createInterface({ input: child.stdout }).on("line", (line) => {
      if (!line.trim()) return;
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        this.emit("protocolError", new Error(`JSON inválido de App Server: ${line}`));
        return;
      }
      this.#handleMessage(message);
    });

    createInterface({ input: child.stderr }).on("line", (line) => {
      this.stderrTail.push(line);
      if (this.stderrTail.length > 30) this.stderrTail.shift();
      this.emit("log", line);
    });

    await this.#requestStarted("initialize", {
      clientInfo: {
        name: "codex-watch-bridge",
        title: "Codex Watch Bridge",
        version: "0.1.0",
      },
      capabilities: { experimentalApi: false },
    });
    this.notify("initialized", {});
  }

  async stop() {
    if (!this.child) return;
    const child = this.child;
    this.child = null;
    const exited = new Promise((resolve) => child.once("exit", resolve));
    child.kill();
    this.#failAll(new Error("Codex App Server detenido"));
    await Promise.race([
      exited,
      new Promise((resolve) => setTimeout(resolve, 5_000)),
    ]);
  }

  async recycle() {
    if (this.recyclePromise) return this.recyclePromise;
    const operation = (async () => {
      await this.stop();
      await this.start();
    })();
    this.recyclePromise = operation;
    try {
      await operation;
    } finally {
      if (this.recyclePromise === operation) this.recyclePromise = null;
    }
  }

  async request(method, params = {}, timeoutMs = this.requestTimeoutMs) {
    if (this.recyclePromise) await this.recyclePromise;
    if (!this.child) await this.start();
    return this.#requestStarted(method, params, timeoutMs);
  }

  #requestStarted(method, params = {}, timeoutMs = this.requestTimeoutMs) {
    if (!this.child?.stdin?.writable) {
      return Promise.reject(new Error("Codex App Server no está iniciado"));
    }
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Tiempo agotado esperando ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, method });
      this.child.stdin.write(`${JSON.stringify({ method, id, params })}\n`);
    });
  }

  notify(method, params = {}) {
    if (!this.child?.stdin?.writable) throw new Error("Codex App Server no está iniciado");
    this.child.stdin.write(`${JSON.stringify({ method, params })}\n`);
  }

  listThreads({ limit = 100, searchTerm = null, archived = false } = {}) {
    return this.request("thread/list", {
      cursor: null,
      limit,
      sortKey: "recency_at",
      sortDirection: "desc",
      sourceKinds: ALL_THREAD_SOURCES,
      archived,
      searchTerm: searchTerm || null,
    });
  }

  readThread(threadId) {
    return this.request("thread/read", { threadId, includeTurns: true });
  }

  unsubscribeThread(threadId) {
    return this.request("thread/unsubscribe", { threadId });
  }

  async #startTextTurn(threadId, text, { clientUserMessageId = null } = {}) {
    await this.request("thread/resume", { threadId });
    try {
      const started = await this.request("turn/start", {
        threadId,
        clientUserMessageId,
        input: [{ type: "text", text, text_elements: [] }],
      });
      if (!started.turn?.id) throw new Error("Codex no devolvió el identificador del turno");
      this.activeTurns += 1;
      return started;
    } catch (error) {
      await this.#releaseThread(threadId);
      throw error;
    }
  }

  async #unsubscribeSafely(threadId) {
    try {
      return await this.unsubscribeThread(threadId);
    } catch (error) {
      this.emit(
        "protocolError",
        new Error(`No se pudo liberar la tarea ${threadId}: ${error.message}`),
      );
      return null;
    }
  }

  async #releaseThread(threadId, { activeTurn = false } = {}) {
    await this.#unsubscribeSafely(threadId);
    if (activeTurn) this.activeTurns = Math.max(0, this.activeTurns - 1);
    if (this.activeTurns === 0) await this.recycle();
  }

  async sendText(threadId, text, options = {}) {
    const started = await this.#startTextTurn(threadId, text, options);
    const turn = started.turn;
    const completion = turn.status === "completed" || turn.status === "failed" || turn.status === "interrupted"
      ? Promise.resolve(turn)
      : this.waitForTurn(threadId, turn.id, options.timeoutMs);
    void completion
      .catch((error) => {
        this.emit("protocolError", error);
      })
      .finally(() => this.#releaseThread(threadId, { activeTurn: true }));
    return started;
  }

  async sendTextAndWait(threadId, text, options = {}) {
    const started = await this.#startTextTurn(threadId, text, options);
    try {
      const turn = started.turn;
      const completed = turn.status === "completed" || turn.status === "failed" || turn.status === "interrupted"
        ? turn
        : await this.waitForTurn(threadId, turn.id, options.timeoutMs);
      const detail = await this.readThread(threadId);
      return { turn: completed, thread: detail.thread };
    } finally {
      await this.#releaseThread(threadId, { activeTurn: true });
    }
  }

  waitForTurn(threadId, turnId, timeoutMs = 30 * 60 * 1000) {
    const key = `${threadId}:${turnId}`;
    if (this.completedTurns.has(key)) {
      const turn = this.completedTurns.get(key);
      this.completedTurns.delete(key);
      return Promise.resolve(turn);
    }
    return new Promise((resolve, reject) => {
      const onNotification = (message) => {
        if (message.method !== "turn/completed") return;
        if (message.params?.threadId !== threadId || message.params?.turn?.id !== turnId) return;
        cleanup();
        this.completedTurns.delete(key);
        resolve(message.params.turn);
      };
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error("Tiempo agotado esperando la respuesta de Codex"));
      }, timeoutMs);
      const cleanup = () => {
        clearTimeout(timer);
        this.off("notification", onNotification);
      };
      this.on("notification", onNotification);
    });
  }

  #handleMessage(message) {
    if (Object.hasOwn(message, "id") && this.pending.has(message.id)) {
      const pending = this.pending.get(message.id);
      clearTimeout(pending.timer);
      this.pending.delete(message.id);
      if (message.error) {
        const error = new Error(message.error.message ?? `Error en ${pending.method}`);
        error.code = message.error.code;
        error.data = message.error.data;
        pending.reject(error);
      } else {
        pending.resolve(message.result);
      }
      return;
    }
    if (Object.hasOwn(message, "id") && message.method) {
      this.emit("request", message);
      const safeDeclines = new Set([
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "execCommandApproval",
        "applyPatchApproval",
      ]);
      if (safeDeclines.has(message.method)) {
        this.child?.stdin?.write(`${JSON.stringify({ id: message.id, result: { decision: "decline" } })}\n`);
      } else {
        this.child?.stdin?.write(
          `${JSON.stringify({
            id: message.id,
            error: { code: -32601, message: `Petición no soportada por Codex Watch: ${message.method}` },
          })}\n`,
        );
      }
      return;
    }
    if (message.method === "turn/completed") {
      const threadId = message.params?.threadId;
      const turn = message.params?.turn;
      if (threadId && turn?.id) {
        this.completedTurns.set(`${threadId}:${turn.id}`, turn);
        while (this.completedTurns.size > 100) {
          this.completedTurns.delete(this.completedTurns.keys().next().value);
        }
      }
    }
    if (message.method) this.emit("notification", message);
  }

  #failAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}
