import {
  createHash,
  generateKeyPairSync,
  sign,
} from "node:crypto";
import { mkdtempSync } from "node:fs";
import { request } from "node:https";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { test } from "node:test";
import assert from "node:assert/strict";

const bridgeDirectory = resolve(import.meta.dirname, "..");
const bridgeScript = join(bridgeDirectory, "soma-bridge.mjs");
const mockCodex = join(import.meta.dirname, "mock-codex.mjs");

test("pairs a device, verifies signed requests, and completes a Codex turn", async (context) => {
  const stateDirectory = mkdtempSync(join(tmpdir(), "soma-bridge-test-"));
  const port = 18_843 + Math.floor(Math.random() * 1_000);
  const child = spawn(process.execPath, [bridgeScript], {
    cwd: bridgeDirectory,
    env: {
      ...process.env,
      CODEX_BIN: mockCodex,
      SOMA_BRIDGE_HOME: stateDirectory,
      SOMA_BRIDGE_HOST: "127.0.0.1",
      SOMA_BRIDGE_PORT: String(port),
      SOMA_BRIDGE_PAIRING_SECONDS: "120",
      SOMA_BRIDGE_TURNS_PER_HOUR: "1",
      SOMA_MOCK_TURN_DELAY_MS: "250",
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
  context.after(() => child.kill("SIGTERM"));

  let output = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    output += chunk;
  });
  let errors = "";
  child.stderr.on("data", (chunk) => {
    errors += chunk;
  });

  const pairingURI = await waitFor(() => {
    const match = output.match(/soma:\/\/pair\?[^\s]+/);
    return match?.[0];
  });
  assert.ok(pairingURI, errors);
  const pairing = new URL(pairingURI);
  const secret = pairing.searchParams.get("secret");
  const bridgeID = pairing.searchParams.get("bridge");
  const certificateFingerprint = pairing.searchParams.get("fingerprint");
  assert.ok(secret);
  assert.ok(bridgeID);
  assert.match(certificateFingerprint, /^[0-9a-f]{64}$/);

  const keys = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const jwk = keys.publicKey.export({ format: "jwk" });
  const publicKey = Buffer.concat([
    Buffer.from([0x04]),
    Buffer.from(jwk.x, "base64url"),
    Buffer.from(jwk.y, "base64url"),
  ]).toString("base64");
  const deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B";

  const pairBody = {
    protocolVersion: 1,
    deviceID,
    deviceName: "Test iPhone",
    platform: "ios",
    publicKey,
    requestedCapabilities: [
      "context.read",
      "codex.thread",
      "codex.turn",
      "codex.stream",
    ],
  };
  const forwardedPair = await jsonRequest({
    port,
    method: "POST",
    path: "/v1/pair",
    body: pairBody,
    headers: {
      "X-Soma-Pairing-Secret": secret,
      "X-Forwarded-For": "203.0.113.7",
    },
    expectedFingerprint: certificateFingerprint,
  });
  assert.equal(forwardedPair.status, 403);
  assert.equal(
    forwardedPair.body.error.code,
    "direct_connection_required",
  );
  const paired = await atStage("pair", errors, jsonRequest({
    port,
    method: "POST",
    path: "/v1/pair",
    body: pairBody,
    headers: { "X-Soma-Pairing-Secret": secret },
    expectedFingerprint: certificateFingerprint,
  }));
  assert.equal(paired.status, 201);
  assert.equal(paired.body.deviceID, deviceID);

  let sequence = 0;
  const prepareSignedRequest = (method, path, body, reusedNonce) => {
    sequence += 1;
    const requestSequence = sequence;
    const encoded = body === undefined
      ? Buffer.alloc(0)
      : Buffer.from(JSON.stringify(body));
    const timestamp = Date.now();
    const nonce =
      reusedNonce ?? `nonce_${requestSequence.toString().padStart(16, "0")}`;
    const canonical = [
      "SOMA-BRIDGE-REQUEST",
      "1",
      bridgeID,
      certificateFingerprint,
      method,
      path,
      createHash("sha256").update(encoded).digest("hex"),
      deviceID,
      String(requestSequence),
      String(timestamp),
      nonce,
    ].join("\n");
    const signature = sign(
      "sha256",
      Buffer.from(canonical),
      keys.privateKey,
    ).toString("base64");
    return {
      nonce,
      send: () => jsonRequest({
        port,
        method,
        path,
        encodedBody: encoded,
        headers: {
          "X-Soma-Device": deviceID,
          "X-Soma-Sequence": String(requestSequence),
          "X-Soma-Timestamp": String(timestamp),
          "X-Soma-Nonce": nonce,
          "X-Soma-Signature": signature,
        },
        expectedFingerprint: certificateFingerprint,
      }),
    };
  };
  const signedRequest = (method, path, body) =>
    prepareSignedRequest(method, path, body).send();

  const statusOperation = prepareSignedRequest("GET", "/v1/status");
  const status = await atStage(
    "status",
    errors,
    statusOperation.send(),
  );
  assert.equal(status.status, 200);
  assert.equal(status.body.codexReady, true);
  const repeatedStatus = await statusOperation.send();
  assert.deepEqual(repeatedStatus, status);

  const fixture = JSON.parse(
    await import("node:fs/promises").then(({ readFile }) =>
      readFile(
        resolve(bridgeDirectory, "../protocol/v1/golden/turn-request.json"),
        "utf8",
      ),
    ),
  );
  const turnOperation = prepareSignedRequest(
    "POST",
    "/v1/codex/turns",
    fixture,
  );
  const started = await atStage(
    "turn start",
    errors,
    turnOperation.send(),
  );
  assert.equal(started.status, 202, JSON.stringify(started.body));
  assert.equal(started.body.status, "running");
  const exactRetry = await turnOperation.send();
  assert.equal(exactRetry.status, 202);
  assert.deepEqual(exactRetry.body, started.body);
  const conflictingNonce = await prepareSignedRequest(
    "GET",
    "/v1/status",
    undefined,
    turnOperation.nonce,
  ).send();
  assert.equal(conflictingNonce.status, 409);
  assert.equal(conflictingNonce.body.error.code, "nonce_reused");
  const concurrentTurn = await signedRequest(
    "POST",
    "/v1/codex/turns",
    fixture,
  );
  assert.equal(concurrentTurn.status, 429);
  assert.equal(concurrentTurn.body.error.code, "codex_busy");

  const completed = await waitFor(async () => {
    const response = await signedRequest(
      "GET",
      `/v1/codex/jobs/${started.body.jobID}`,
    );
    return response.body.status === "completed" ? response.body : null;
  });
  assert.match(completed.output, /garden meeting/);
  const rateLimitedTurn = await signedRequest(
    "POST",
    "/v1/codex/turns",
    fixture,
  );
  assert.equal(rateLimitedTurn.status, 429);
  assert.equal(rateLimitedTurn.body.error.code, "codex_rate_limited");
});

function jsonRequest({
  port,
  method,
  path,
  body,
  encodedBody,
  headers = {},
  expectedFingerprint,
}) {
  const data = encodedBody ?? (
    body === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(body))
  );
  return new Promise((resolvePromise, rejectPromise) => {
    const req = request(
      {
        hostname: "127.0.0.1",
        port,
        method,
        path,
        agent: false,
        rejectUnauthorized: false,
        headers: {
          ...headers,
          ...(data.length
            ? {
                "Content-Type": "application/json",
                "Content-Length": data.length,
              }
            : {}),
        },
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          resolvePromise({
            status: response.statusCode,
            body: text ? JSON.parse(text) : {},
          });
        });
      },
    );
    req.on("error", rejectPromise);
    const send = () => {
      if (data.length) req.write(data);
      req.end();
    };
    if (expectedFingerprint) {
      req.on("socket", (socket) => {
        socket.once("secureConnect", () => {
          const certificate = socket.getPeerCertificate();
          const actualFingerprint = certificate.raw
            ? createHash("sha256").update(certificate.raw).digest("hex")
            : "";
          if (actualFingerprint !== expectedFingerprint) {
            req.destroy(new Error("Pinned bridge certificate did not match."));
            return;
          }
          send();
        });
      });
    } else {
      send();
    }
  });
}

async function waitFor(producer, timeout = 10_000) {
  const deadline = Date.now() + timeout;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await producer();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  }
  throw lastError ?? new Error("Timed out waiting for condition.");
}

async function atStage(name, bridgeErrors, promise) {
  try {
    return await promise;
  } catch (error) {
    throw new Error(
      `${name}: ${error.message}${bridgeErrors ? `\n${bridgeErrors}` : ""}`,
      { cause: error },
    );
  }
}
