function threadStatus(status) {
  if (!status) return "unknown";
  return typeof status === "string" ? status : status.type ?? "unknown";
}

export function summarizeThread(thread) {
  const title = thread.name?.trim() || thread.preview?.trim() || "Conversación sin título";
  return {
    id: thread.id,
    title,
    preview: thread.preview ?? "",
    cwd: thread.cwd ?? null,
    createdAt: thread.createdAt ?? null,
    updatedAt: thread.updatedAt ?? null,
    recencyAt: thread.recencyAt ?? thread.updatedAt ?? thread.createdAt ?? null,
    status: threadStatus(thread.status),
  };
}

function userText(content = []) {
  return content
    .map((part) => {
      if (part.type === "text") return part.text;
      if (part.type === "image" || part.type === "localImage") return "[Imagen]";
      if (part.type === "audio" || part.type === "localAudio") return "[Audio]";
      if (part.type === "skill") return `[Skill: ${part.name ?? "desconocida"}]`;
      return `[${part.type ?? "contenido"}]`;
    })
    .filter(Boolean)
    .join("\n");
}

export function normalizeMessages(thread) {
  const messages = [];
  for (const turn of thread.turns ?? []) {
    for (const item of turn.items ?? []) {
      if (item.type === "userMessage") {
        messages.push({
          id: item.id,
          turnId: turn.id,
          role: "user",
          text: userText(item.content),
          phase: null,
          turnStatus: turn.status ?? null,
          timestamp: turn.startedAt ?? null,
        });
      } else if (item.type === "agentMessage") {
        messages.push({
          id: item.id,
          turnId: turn.id,
          role: "assistant",
          text: item.text ?? "",
          phase: item.phase ?? null,
          turnStatus: turn.status ?? null,
          timestamp: turn.completedAt ?? turn.startedAt ?? null,
        });
      }
    }
  }
  return messages;
}

export function detailedThread(thread) {
  return { ...summarizeThread(thread), messages: normalizeMessages(thread) };
}

export function watchThread(thread, { includeMessages = true } = {}) {
  const detail = detailedThread(thread);
  // The watch UI only renders the last assistant response. Send that response
  // at useful reading length instead of four separately truncated messages.
  const clipUtf8 = (value, maxBytes) => {
    const text = String(value ?? "");
    const bytes = new TextEncoder().encode(text);
    if (bytes.length <= maxBytes) return text;
    let end = maxBytes;
    while (end > 0 && (bytes[end] & 0xc0) === 0x80) end--;
    return `${new TextDecoder().decode(bytes.slice(0, end))}…`;
  };
  const lastAssistant = detail.messages.findLast(({ role }) => role === "assistant");
  return {
    id: clipUtf8(detail.id, 39),
    title: clipUtf8(detail.title, 90),
    preview: clipUtf8(detail.preview, 150),
    messages: includeMessages && lastAssistant ? [{
      role: "assistant",
      text: clipUtf8(lastAssistant.text, 23_500),
    }] : [],
  };
}
