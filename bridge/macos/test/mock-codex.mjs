#!/usr/bin/env node

import { createInterface } from "node:readline";

if (process.argv.includes("features") && process.argv.includes("list")) {
  for (const feature of [
    "enable_request_compression",
    "fast_mode",
    "mentions_v2",
    "personality",
    "remote_compaction_v2",
  ]) {
    process.stdout.write(`${feature} stable true\n`);
  }
  for (const feature of [
    "apps",
    "artifact",
    "auth_elicitation",
    "browser_use",
    "code_mode",
    "computer_use",
    "image_generation",
    "multi_agent",
    "shell_tool",
    "unified_exec",
  ]) {
    process.stdout.write(`${feature} stable false\n`);
  }
  process.exit(0);
}

const reader = createInterface({ input: process.stdin });
let turnNumber = 0;
reader.on("line", (line) => {
  const message = JSON.parse(line);
  if (message.method === "initialize") {
    send({ id: message.id, result: { userAgent: "mock-codex" } });
  } else if (message.method === "config/read") {
    send({
      id: message.id,
      result: {
        config: {
          features: {},
          mcp_servers: {},
          plugins: {},
        },
        origins: {},
      },
    });
  } else if (message.method === "account/read") {
    send({
      id: message.id,
      result: {
        account: { type: "chatgpt", email: null, planType: "unknown" },
        requiresOpenaiAuth: true,
      },
    });
  } else if (message.method === "thread/start") {
    send({
      id: message.id,
      result: {
        approvalPolicy: "never",
        approvalsReviewer: "user",
        cwd: process.cwd(),
        model: "mock",
        modelProvider: "mock",
        sandbox: { type: "readOnly", networkAccess: false },
        thread: { id: "thr_soma_test" },
      },
    });
  } else if (message.method === "turn/start") {
    turnNumber += 1;
    const turn = {
      id: `019f6a22-3a73-70f2-850f-${turnNumber.toString().padStart(12, "0")}`,
      items: [],
      status: "inProgress",
    };
    send({ id: message.id, result: { turn } });
    setTimeout(() => {
      send({
        method: "item/agentMessage/delta",
        params: {
          threadId: "thr_soma_test",
          turnId: turn.id,
          itemId: "message_1",
          delta: "Marta and September connect through the garden meeting.",
        },
      });
      send({
        method: "turn/completed",
        params: {
          threadId: "thr_soma_test",
          turn: {
            ...turn,
            status: "completed",
            items: [
              {
                id: "message_1",
                type: "agentMessage",
                phase: "final_answer",
                text: "Marta and September connect through the garden meeting.",
              },
            ],
          },
        },
      });
    }, Number(process.env.SOMA_MOCK_TURN_DELAY_MS ?? 25));
  } else if (message.method === "turn/interrupt") {
    send({ id: message.id, result: {} });
  }
});

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}
