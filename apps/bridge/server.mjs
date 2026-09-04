import http from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { CodexAppServer } from "./src/codex-app-server.mjs";
import { closeCodexDesktop, isCodexDesktopOpen } from "./src/codex-desktop.mjs";
import { detailedThread, summarizeThread, watchThread } from "./src/transform.mjs";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC = path.join(ROOT, "public");
const HOST = process.env.CODEX_WATCH_HOST || "127.0.0.1";
const PORT = Number.parseInt(process.env.CODEX_WATCH_PORT || "8787", 10);
const TOKEN = process.env.CODEX_WATCH_TOKEN || "";

if (!Number.isInteger(PORT) || PORT < 1 || PORT > 65535) {
  throw new Error("CODEX_WATCH_PORT debe ser un puerto válido");
}
const codex = new CodexAppServer();
codex.on("protocolError", (error) => console.error(error.message));
codex.on("log", (line) => {
  if (process.env.CODEX_WATCH_DEBUG === "1") console.error(`[codex] ${line}`);
});

function json(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
  });
  response.end(body);
}

function authorized(request, url) {
  if (!TOKEN) return true;
  const bearer = request.headers.authorization?.replace(/^Bearer\s+/i, "");
  return bearer === TOKEN;
}

function desktopCloseAction(threadId = null) {
  return {
    version: 1,
    selectedThreadId: threadId,
    actionRequired: {
      type: "close_codex_desktop",
      message: "Codex Desktop está abierto. Hay que cerrarlo antes de usar esta tarea desde el reloj.",
      confirmLabel: "Cerrar Codex",
      cancelLabel: "Cancelar",
    },
  };
}

async function bodyJson(request) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > 64 * 1024) throw new Error("Petición demasiado grande");
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

async function serveStatic(url, response) {
  const assets = {
    "/": ["index.html", "text/html; charset=utf-8"],
    "/index.html": ["index.html", "text/html; charset=utf-8"],
    "/app.js": ["app.js", "text/javascript; charset=utf-8"],
    "/styles.css": ["styles.css", "text/css; charset=utf-8"],
  };
  const asset = assets[url.pathname];
  if (!asset) return false;
  const data = await readFile(path.join(PUBLIC, asset[0]));
  response.writeHead(200, {
    "content-type": asset[1],
    "content-length": data.length,
    "cache-control": "no-cache",
    "content-security-policy": "default-src 'self'; connect-src 'self'; img-src 'self'; style-src 'self'; script-src 'self'; base-uri 'none'; frame-ancestors 'none'",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
  });
  response.end(data);
  return true;
}

async function route(request, response) {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);

  if (url.pathname.startsWith("/api/") && !authorized(request, url)) {
    return json(response, 401, { error: "Token incorrecto o ausente" });
  }

  if (request.method === "GET" && url.pathname === "/api/health") {
    return json(response, 200, {
      ok: true,
      codexBinary: codex.binary,
      protected: Boolean(TOKEN),
    });
  }

  if (request.method === "GET" && url.pathname === "/api/desktop/status") {
    return json(response, 200, { open: await isCodexDesktopOpen() });
  }

  if (request.method === "POST" && url.pathname === "/api/desktop/close") {
    const body = await bodyJson(request);
    if (body.action !== "close_codex_desktop" || body.confirmed !== true) {
      return json(response, 400, { error: "La orden de cierre necesita confirmación explícita" });
    }
    const result = await closeCodexDesktop();
    const message = !result.wasOpen
      ? "Codex Desktop ya estaba cerrado."
      : result.closed
        ? result.forced
          ? "Codex Desktop no respondió y se cerró de forma forzada."
          : "Codex Desktop se cerró correctamente."
        : "No se pudo cerrar Codex Desktop.";
    console.log(`[desktop-close] ${JSON.stringify({ ...result, message })}`);
    // La orden se procesó correctamente aunque Windows no consiguiera cerrar
    // la aplicación. El móvil necesita el JSON para mostrar el resultado real.
    return json(response, 200, { ...result, message });
  }

  if (request.method === "GET" && url.pathname === "/api/threads") {
    const requestedLimit = Number.parseInt(url.searchParams.get("limit") || "100", 10);
    const limit = Math.min(Math.max(requestedLimit || 100, 1), 200);
    const searchTerm = url.searchParams.get("search")?.trim() || null;
    const result = await codex.listThreads({ limit, searchTerm });
    return json(response, 200, {
      threads: (result.data ?? []).map(summarizeThread),
      nextCursor: result.nextCursor ?? null,
    });
  }

  if (request.method === "GET" && url.pathname === "/api/watch") {
    const result = await codex.listThreads({ limit: 8 });
    const summaries = result.data ?? [];
    const threads = await Promise.all(
      summaries.map(async (summary) => {
        const detail = await codex.readThread(summary.id);
        return watchThread(detail.thread, { includeMessages: false });
      }),
    );
    return json(response, 200, { version: 1, threads });
  }

  const watchThreadMatch = url.pathname.match(/^\/api\/watch\/threads\/([^/]+)$/);
  if (request.method === "GET" && watchThreadMatch) {
    const threadId = decodeURIComponent(watchThreadMatch[1]);
    if (await isCodexDesktopOpen()) {
      return json(response, 200, desktopCloseAction(threadId));
    }
    const result = await codex.readThread(threadId);
    const thread = watchThread(result.thread);
    return json(response, 200, {
      version: 1,
      selectedThreadId: thread.id,
      threads: [thread],
    });
  }

  const watchSendMatch = url.pathname.match(/^\/api\/watch\/threads\/([^/]+)\/messages$/);
  if (request.method === "POST" && watchSendMatch) {
    const threadId = decodeURIComponent(watchSendMatch[1]);
    if (await isCodexDesktopOpen()) {
      return json(response, 409, {
        code: "codex_desktop_open",
        ...desktopCloseAction(threadId),
      });
    }
    const body = await bodyJson(request);
    const text = typeof body.text === "string" ? body.text.trim() : "";
    const clientUserMessageId = typeof body.clientMessageId === "string"
      ? body.clientMessageId.trim() || null
      : null;
    if (!text) return json(response, 400, { error: "El mensaje está vacío" });
    if (text.length > 12_000) return json(response, 400, { error: "Mensaje demasiado largo" });
    const result = await codex.sendTextAndWait(threadId, text, { clientUserMessageId });
    const thread = watchThread(result.thread);
    return json(response, 200, {
      version: 1,
      selectedThreadId: thread.id,
      turnStatus: result.turn.status,
      threads: [thread],
    });
  }

  const match = url.pathname.match(/^\/api\/threads\/([^/]+)$/);
  if (request.method === "GET" && match) {
    const threadId = decodeURIComponent(match[1]);
    const result = await codex.readThread(threadId);
    return json(response, 200, { thread: detailedThread(result.thread) });
  }

  const sendMatch = url.pathname.match(/^\/api\/threads\/([^/]+)\/messages$/);
  if (request.method === "POST" && sendMatch) {
    const threadId = decodeURIComponent(sendMatch[1]);
    const body = await bodyJson(request);
    const text = typeof body.text === "string" ? body.text.trim() : "";
    if (!text) return json(response, 400, { error: "El mensaje está vacío" });
    if (text.length > 12_000) return json(response, 400, { error: "Mensaje demasiado largo" });
    const result = await codex.sendText(threadId, text);
    return json(response, 202, { turn: result.turn });
  }

  if (request.method === "GET" && (await serveStatic(url, response))) return;
  json(response, 404, { error: "No encontrado" });
}

await codex.start();
const server = http.createServer((request, response) => {
  route(request, response).catch((error) => {
    console.error(error);
    if (!response.headersSent) json(response, 500, { error: error.message || "Error interno" });
    else response.end();
  });
});

server.listen(PORT, HOST, () => {
  console.log(`Codex Watch Bridge listo en http://${HOST}:${PORT}`);
  console.log(`App Server: ${codex.binary}`);
  console.log(TOKEN ? "API protegida con token." : "API sin token (modo prototipo).");
});

async function shutdown() {
  server.close();
  await codex.stop();
  process.exit(0);
}
process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
