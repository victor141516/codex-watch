const $ = (selector) => document.querySelector(selector);
const state = {
  token: sessionStorage.getItem("codex-watch-token") || "",
  threadId: null,
  loading: false,
  searchTimer: null,
};

function headers(json = false) {
  const value = {};
  if (json) value["content-type"] = "application/json";
  if (state.token) value.authorization = `Bearer ${state.token}`;
  return value;
}

async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { ...headers(Boolean(options.body)), ...options.headers } });
  const data = await response.json().catch(() => ({}));
  if (response.status === 401) {
    $("#token-panel").classList.remove("hidden");
    throw new Error("Introduce el token del servidor");
  }
  if (!response.ok) throw new Error(data.error || `Error HTTP ${response.status}`);
  $("#token-panel").classList.add("hidden");
  return data;
}

function showError(error) {
  const box = $("#error");
  box.textContent = error?.message || String(error);
  box.classList.remove("hidden");
  clearTimeout(showError.timer);
  showError.timer = setTimeout(() => box.classList.add("hidden"), 6000);
}

function relativeTime(value) {
  if (!value) return "";
  const date = typeof value === "number" ? new Date(value * 1000) : new Date(value);
  if (Number.isNaN(date.valueOf())) return "";
  const seconds = Math.round((date.valueOf() - Date.now()) / 1000);
  const formatter = new Intl.RelativeTimeFormat("es", { numeric: "auto" });
  const ranges = [[60, "second"], [60, "minute"], [24, "hour"], [7, "day"], [4.35, "week"], [12, "month"], [Infinity, "year"]];
  let amount = seconds;
  for (const [size, unit] of ranges) {
    if (Math.abs(amount) < size) return formatter.format(Math.round(amount), unit);
    amount /= size;
  }
  return "";
}

function statusLabel(status) {
  return ({ active: "Trabajando", idle: "En espera", notLoaded: "Guardada", systemError: "Error" })[status] || status || "";
}

async function loadThreads() {
  if (state.loading) return;
  state.loading = true;
  $("#thread-list").innerHTML = '<div class="empty">Cargando tareas…</div>';
  try {
    const search = $("#search").value.trim();
    const data = await api(`/api/threads?limit=100&search=${encodeURIComponent(search)}`);
    $("#count").textContent = data.threads.length;
    if (!data.threads.length) {
      $("#thread-list").innerHTML = '<div class="empty">No hay tareas que mostrar.</div>';
      return;
    }
    $("#thread-list").replaceChildren(...data.threads.map(threadCard));
  } catch (error) {
    $("#thread-list").innerHTML = '<div class="empty">No se pudo cargar.</div>';
    showError(error);
  } finally {
    state.loading = false;
  }
}

function threadCard(thread) {
  const button = document.createElement("button");
  button.className = "thread-card";
  button.innerHTML = `
    <span class="thread-copy">
      <strong></strong>
      <small></small>
    </span>
    <span class="thread-meta"><time></time><b aria-hidden="true">›</b></span>`;
  button.querySelector("strong").textContent = thread.title;
  button.querySelector("small").textContent = thread.preview || thread.cwd || "Sin vista previa";
  button.querySelector("time").textContent = relativeTime(thread.recencyAt);
  button.addEventListener("click", () => openThread(thread.id));
  return button;
}

async function openThread(threadId, { quiet = false } = {}) {
  state.threadId = threadId;
  $("#list-view").classList.add("hidden");
  $("#thread-view").classList.remove("hidden");
  if (!quiet) $("#messages").innerHTML = '<div class="empty">Cargando conversación…</div>';
  try {
    const { thread } = await api(`/api/threads/${encodeURIComponent(threadId)}`);
    if (state.threadId !== threadId) return;
    $("#thread-title").textContent = thread.title;
    $("#status").textContent = statusLabel(thread.status);
    const wasNearBottom = $("#messages").scrollHeight - $("#messages").scrollTop - $("#messages").clientHeight < 100;
    $("#messages").replaceChildren(...thread.messages.map(messageBubble));
    if (!thread.messages.length) $("#messages").innerHTML = '<div class="empty">Esta tarea aún no tiene mensajes.</div>';
    if (!quiet || wasNearBottom) $("#messages").scrollTop = $("#messages").scrollHeight;
  } catch (error) {
    showError(error);
  }
}

function messageBubble(message) {
  const article = document.createElement("article");
  article.className = `message ${message.role}`;
  const label = document.createElement("span");
  label.className = "message-role";
  label.textContent = message.role === "user" ? "Tú" : "Codex";
  const text = document.createElement("div");
  text.className = "message-text";
  text.textContent = message.text || "…";
  article.append(label, text);
  return article;
}

$("#back").addEventListener("click", () => {
  state.threadId = null;
  $("#thread-view").classList.add("hidden");
  $("#list-view").classList.remove("hidden");
  loadThreads();
});
$("#refresh").addEventListener("click", () => state.threadId ? openThread(state.threadId) : loadThreads());
$("#search").addEventListener("input", () => {
  clearTimeout(state.searchTimer);
  state.searchTimer = setTimeout(loadThreads, 250);
});
$("#save-token").addEventListener("click", () => {
  state.token = $("#token").value.trim();
  sessionStorage.setItem("codex-watch-token", state.token);
  state.threadId ? openThread(state.threadId) : loadThreads();
});
$("#composer").addEventListener("submit", async (event) => {
  event.preventDefault();
  const text = $("#message").value.trim();
  if (!text || !state.threadId) return;
  $("#send").disabled = true;
  try {
    await api(`/api/threads/${encodeURIComponent(state.threadId)}/messages`, {
      method: "POST",
      body: JSON.stringify({ text }),
    });
    $("#message").value = "";
    setTimeout(() => openThread(state.threadId, { quiet: true }), 500);
  } catch (error) {
    showError(error);
  } finally {
    $("#send").disabled = false;
  }
});

setInterval(() => {
  if (document.visibilityState === "visible" && state.threadId) openThread(state.threadId, { quiet: true });
}, 4000);
loadThreads();
