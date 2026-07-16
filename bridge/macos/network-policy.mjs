import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { networkInterfaces } from "node:os";

export const BridgeNetwork = Object.freeze({
  LAN: "lan",
  TAILSCALE: "tailscale",
});

export function classifyBridgeIPv4(value) {
  const octets = parseIPv4(value);
  if (!octets) return null;
  if (octets[0] === 127) return "loopback";
  if (
    octets[0] === 10 ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168)
  ) {
    return BridgeNetwork.LAN;
  }
  if (octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127) {
    return BridgeNetwork.TAILSCALE;
  }
  return null;
}

export function isAllowedBridgePeerForNetwork(value, network) {
  if (typeof value !== "string" || !value) return false;
  let address = value.toLowerCase();
  if (address.startsWith("::ffff:")) address = address.slice(7);
  const classification = classifyBridgeIPv4(address);
  if (network === BridgeNetwork.TAILSCALE) {
    return classification === BridgeNetwork.TAILSCALE;
  }
  return classification === BridgeNetwork.LAN || classification === "loopback";
}

export function resolveBridgeListenEndpoint({
  environment = process.env,
  interfaces = networkInterfaces(),
  tailscaleStatusLoader = () => readTailscaleStatus(environment),
} = {}) {
  const mode = environment.SOMA_BRIDGE_NETWORK || BridgeNetwork.LAN;
  if (!Object.values(BridgeNetwork).includes(mode)) {
    throw new Error("SOMA_BRIDGE_NETWORK must be `lan` or `tailscale`.");
  }

  const explicitHost = environment.SOMA_BRIDGE_HOST;
  if (explicitHost) {
    const classification = classifyBridgeIPv4(explicitHost);
    if (!classification) {
      throw new Error(
        "SOMA_BRIDGE_HOST must be loopback, RFC1918 private IPv4, or Tailscale 100.64.0.0/10.",
      );
    }
    const matchesMode =
      mode === BridgeNetwork.TAILSCALE
        ? classification === BridgeNetwork.TAILSCALE
        : classification === BridgeNetwork.LAN || classification === "loopback";
    if (!matchesMode) {
      throw new Error(
        `${mode} mode does not match SOMA_BRIDGE_HOST ${explicitHost}.`,
      );
    }
    if (mode === BridgeNetwork.TAILSCALE) {
      requireConfirmedTailscaleAddress(
        explicitHost,
        interfaces,
        tailscaleStatusLoader,
      );
    }
    return { host: explicitHost, network: mode };
  }

  const selected = selectInterfaceAddress(interfaces, mode);
  if (selected) {
    if (mode === BridgeNetwork.TAILSCALE) {
      requireConfirmedTailscaleAddress(
        selected,
        interfaces,
        tailscaleStatusLoader,
      );
    }
    return { host: selected, network: mode };
  }
  if (mode === BridgeNetwork.TAILSCALE) {
    throw new Error(
      "No active Tailscale IPv4 address was found. Connect this Mac to Tailscale first.",
    );
  }
  return { host: "127.0.0.1", network: mode };
}

function selectInterfaceAddress(interfaces, mode) {
  for (const records of Object.values(interfaces)) {
    for (const record of records ?? []) {
      if (
        (record.family !== "IPv4" && record.family !== 4) ||
        record.internal
      ) {
        continue;
      }
      if (classifyBridgeIPv4(record.address) === mode) {
        return record.address;
      }
    }
  }
  return null;
}

function requireConfirmedTailscaleAddress(
  host,
  interfaces,
  tailscaleStatusLoader,
) {
  const locallyAssigned = Object.values(interfaces).some((records) =>
    (records ?? []).some(
      (record) =>
        (record.family === "IPv4" || record.family === 4) &&
        record.address === host,
    ),
  );
  if (!locallyAssigned) {
    throw new Error(
      "The selected Tailscale address is not assigned to a local interface.",
    );
  }

  const status = tailscaleStatusLoader();
  if (status?.BackendState !== "Running") {
    throw new Error("Tailscale is not connected on this Mac.");
  }
  const reportedAddresses = [
    ...(Array.isArray(status?.Self?.TailscaleIPs)
      ? status.Self.TailscaleIPs
      : []),
    ...(Array.isArray(status?.TailscaleIPs) ? status.TailscaleIPs : []),
  ];
  if (!reportedAddresses.includes(host)) {
    throw new Error(
      "The selected address is not owned by the connected Tailscale node.",
    );
  }
}

function readTailscaleStatus(environment) {
  const executable = resolveTailscaleExecutable(environment);
  const result = spawnSync(executable, ["status", "--json"], {
    encoding: "utf8",
    env: tailscaleEnvironment(environment),
    timeout: 5_000,
    maxBuffer: 1024 * 1024,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      "Could not verify Tailscale status. Install Tailscale, connect it, and try again.",
    );
  }
  try {
    return JSON.parse(result.stdout);
  } catch {
    throw new Error("Tailscale returned an invalid status response.");
  }
}

function resolveTailscaleExecutable(environment) {
  const configured = environment.TAILSCALE_BIN;
  if (configured) return configured;
  for (const candidate of [
    "/usr/local/bin/tailscale",
    "/opt/homebrew/bin/tailscale",
    "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
  ]) {
    if (existsSync(candidate)) return candidate;
  }
  return "tailscale";
}

function tailscaleEnvironment(source) {
  const environment = { TAILSCALE_BE_CLI: "1" };
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
  ]) {
    if (typeof source[name] === "string") {
      environment[name] = source[name];
    }
  }
  return environment;
}

function parseIPv4(value) {
  if (typeof value !== "string") return null;
  const parts = value.split(".");
  if (parts.length !== 4) return null;
  const octets = [];
  for (const part of parts) {
    if (!/^(0|[1-9]\d{0,2})$/.test(part)) return null;
    const octet = Number(part);
    if (octet > 255) return null;
    octets.push(octet);
  }
  return octets;
}
