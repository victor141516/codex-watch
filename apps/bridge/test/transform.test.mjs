import test from "node:test";
import assert from "node:assert/strict";
import { detailedThread, normalizeMessages, summarizeThread, watchThread } from "../src/transform.mjs";

const fixture = {
  id: "thread-1",
  name: "Prueba",
  preview: "Una prueba",
  cwd: "C:\\work",
  createdAt: 10,
  updatedAt: 20,
  status: { type: "idle" },
  turns: [
    {
      id: "turn-1",
      status: "completed",
      startedAt: 11,
      completedAt: 12,
      items: [
        { id: "u1", type: "userMessage", content: [{ type: "text", text: "Hola" }] },
        { id: "a1", type: "agentMessage", text: "Buenas", phase: "final_answer" },
      ],
    },
  ],
};

test("summarizeThread exposes the useful list fields", () => {
  assert.deepEqual(summarizeThread(fixture), {
    id: "thread-1",
    title: "Prueba",
    preview: "Una prueba",
    cwd: "C:\\work",
    createdAt: 10,
    updatedAt: 20,
    recencyAt: 20,
    status: "idle",
  });
});

test("normalizeMessages keeps user and assistant messages in order", () => {
  assert.deepEqual(normalizeMessages(fixture).map(({ role, text }) => ({ role, text })), [
    { role: "user", text: "Hola" },
    { role: "assistant", text: "Buenas" },
  ]);
});

test("detailedThread combines metadata and messages", () => {
  assert.equal(detailedThread(fixture).messages.length, 2);
});

test("watchThread sends the complete last assistant response", () => {
  const longText = "respuesta larga ".repeat(100);
  const thread = structuredClone(fixture);
  thread.turns[0].items.push({
    id: "a2",
    type: "agentMessage",
    text: longText,
    phase: "final_answer",
  });

  assert.deepEqual(watchThread(thread).messages, [
    { role: "assistant", text: longText },
  ]);
});
