#!/usr/bin/env node

import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { spawn, spawnSync } from "node:child_process";
import { createInterface } from "node:readline";
import { resolve } from "node:path";
import {
  CODEX_ALLOWED_ENABLED_FEATURES,
  CODEX_APP_SERVER_ARGUMENTS,
  CODEX_DISABLED_FEATURES,
  CODEX_GLOBAL_ARGUMENTS,
  lockedCodexThreadConfig,
} from "../codex-policy.mjs";

const bundledCodex = "/Applications/ChatGPT.app/Contents/Resources/codex";
const executable =
  process.env.CODEX_BIN ||
  (existsSync(bundledCodex) ? bundledCodex : "codex");
const workingDirectory = resolve(import.meta.dirname, "..");
const featureList = spawnSync(
  executable,
  [...CODEX_GLOBAL_ARGUMENTS, "features", "list"],
  {
    cwd: workingDirectory,
    env: codexEnvironment(),
    encoding: "utf8",
    timeout: 20_000,
  },
);
assert.equal(featureList.status, 0, featureList.stderr);
const featureLines = String(featureList.stdout)
  .split(/\r?\n/)
  .filter((line) => line.trim());
assert.ok(featureLines.length >= 10);
const unexpectedEnabledFeatures = featureLines.flatMap((line) => {
    const match = line.match(/^(\S+)\s+(.+?)\s+(true|false)$/);
    assert.ok(match, `Unrecognized feature-list line: ${line}`);
    if (
      match[3] !== "true" ||
      match[2].trim() === "removed" ||
      CODEX_ALLOWED_ENABLED_FEATURES.has(match[1])
    ) {
      return [];
    }
    return [match[1]];
  });
assert.deepEqual(unexpectedEnabledFeatures, []);

const child = spawn(executable, CODEX_APP_SERVER_ARGUMENTS, {
  cwd: resolve(import.meta.dirname, ".."),
  env: codexEnvironment(),
  stdio: ["pipe", "pipe", "pipe"],
});
let stderr = "";
child.stderr.setEncoding("utf8");
child.stderr.on("data", (chunk) => {
  stderr += chunk;
});

let nextID = 1;
const pending = new Map();
const reader = createInterface({ input: child.stdout });
reader.on("line", (line) => {
  const message = JSON.parse(line);
  if (
    message.id !== undefined &&
    (message.result !== undefined || message.error !== undefined)
  ) {
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) {
      request.reject(new Error(message.error.message ?? "Codex request failed."));
    } else {
      request.resolve(message.result);
    }
    return;
  }
  if (message.id !== undefined && typeof message.method === "string") {
    send({
      id: message.id,
      error: {
        code: -32_601,
        message: "Soma smoke test rejects server-initiated requests.",
      },
    });
  }
});

child.on("exit", (code, signal) => {
  const error = new Error(
    `Codex app-server exited (${code ?? signal ?? "unknown"}).\n${stderr}`,
  );
  for (const request of pending.values()) request.reject(error);
  pending.clear();
});

try {
  await request("initialize", {
    clientInfo: {
      name: "soma_bridge_smoke",
      title: "Soma Bridge Smoke Test",
      version: "0.1.0",
    },
  });
  send({ method: "initialized", params: {} });

  const read = await request("config/read", {
    cwd: workingDirectory,
    includeLayers: false,
  });
  const config = read?.config ?? {};
  assert.equal(config.web_search, "disabled");
  assert.equal(config.history?.persistence, "none");
  assert.equal(config.shell_environment_policy?.inherit, "none");
  const threadConfig = lockedCodexThreadConfig(config);
  assert.ok(
    Object.values(threadConfig.mcp_servers).every(
      (server) => server.enabled === false,
    ),
  );
  assert.ok(
    Object.values(threadConfig.plugins).every(
      (plugin) => plugin.enabled === false,
    ),
  );
  for (const feature of CODEX_DISABLED_FEATURES) {
    assert.equal(config.features?.[feature], false, `${feature} must be disabled`);
  }

  const account = await request("account/read", { refreshToken: false });
  assert.equal(typeof account?.requiresOpenaiAuth, "boolean");

  const started = await request("thread/start", {
    approvalPolicy: "never",
    baseInstructions: "Use supplied text only. Do not use tools.",
    config: threadConfig,
    cwd: workingDirectory,
    ephemeral: true,
    personality: "friendly",
    sandbox: "read-only",
    serviceName: "soma_bridge_smoke",
    threadSource: "soma_bridge",
  });
  assert.equal(started?.thread?.ephemeral, true);
  assert.equal(started?.sandbox?.type, "readOnly");
  process.stdout.write("real-codex-policy-ok\n");
} catch (error) {
  process.stderr.write(`${error.stack ?? error.message}\n${stderr}`);
  process.exitCode = 1;
} finally {
  child.kill("SIGTERM");
}

function request(method, params) {
  return new Promise((resolvePromise, rejectPromise) => {
    const id = nextID++;
    const timeout = setTimeout(() => {
      pending.delete(id);
      rejectPromise(new Error(`Timed out waiting for ${method}.`));
    }, 20_000);
    pending.set(id, {
      resolve: (value) => {
        clearTimeout(timeout);
        resolvePromise(value);
      },
      reject: (error) => {
        clearTimeout(timeout);
        rejectPromise(error);
      },
    });
    send({ id, method, params });
  });
}

function send(message) {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

function codexEnvironment() {
  const environment = {};
  for (const name of [
    "HOME",
    "PATH",
    "USER",
    "LOGNAME",
    "SHELL",
    "TMPDIR",
    "LANG",
    "LC_ALL",
    "TERM",
    "CODEX_HOME",
    "CODEX_SQLITE_HOME",
    "HTTPS_PROXY",
    "HTTP_PROXY",
    "ALL_PROXY",
    "NO_PROXY",
  ]) {
    if (typeof process.env[name] === "string") {
      environment[name] = process.env[name];
    }
  }
  environment.RUST_LOG = "error";
  return environment;
}
