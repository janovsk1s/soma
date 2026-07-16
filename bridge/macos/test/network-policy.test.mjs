import assert from "node:assert/strict";
import { test } from "node:test";
import {
  BridgeNetwork,
  classifyBridgeIPv4,
  isAllowedBridgePeerForNetwork,
  resolveBridgeListenEndpoint,
} from "../network-policy.mjs";

test("classifies LAN and Tailscale addresses without accepting public or DNS hosts", () => {
  assert.equal(classifyBridgeIPv4("192.168.1.42"), BridgeNetwork.LAN);
  assert.equal(classifyBridgeIPv4("100.64.0.1"), BridgeNetwork.TAILSCALE);
  assert.equal(classifyBridgeIPv4("100.127.255.255"), BridgeNetwork.TAILSCALE);
  assert.equal(classifyBridgeIPv4("100.63.255.255"), null);
  assert.equal(classifyBridgeIPv4("100.128.0.0"), null);
  assert.equal(classifyBridgeIPv4("10.0.0.1.attacker.example"), null);
  assert.equal(classifyBridgeIPv4("8.8.8.8"), null);
  assert.equal(classifyBridgeIPv4("010.0.0.1"), null);
});

test("scopes authenticated peers to the selected LAN or tailnet mode", () => {
  assert.equal(
    isAllowedBridgePeerForNetwork(
      "::ffff:100.101.102.103",
      BridgeNetwork.TAILSCALE,
    ),
    true,
  );
  assert.equal(
    isAllowedBridgePeerForNetwork("192.168.1.50", BridgeNetwork.TAILSCALE),
    false,
  );
  assert.equal(
    isAllowedBridgePeerForNetwork("127.0.0.1", BridgeNetwork.TAILSCALE),
    false,
  );
  assert.equal(
    isAllowedBridgePeerForNetwork("192.168.1.50", BridgeNetwork.LAN),
    true,
  );
  assert.equal(
    isAllowedBridgePeerForNetwork("100.101.102.103", BridgeNetwork.LAN),
    false,
  );
  assert.equal(
    isAllowedBridgePeerForNetwork("203.0.113.7", BridgeNetwork.LAN),
    false,
  );
});

test("selects an explicit tailnet mode and fails closed when Tailscale is absent", () => {
  const interfaces = {
    en0: [
      { address: "192.168.1.42", family: "IPv4", internal: false },
    ],
    utun8: [
      { address: "100.101.102.103", family: "IPv4", internal: false },
    ],
  };
  assert.deepEqual(
    resolveBridgeListenEndpoint({
      environment: { SOMA_BRIDGE_NETWORK: "tailscale" },
      interfaces,
      tailscaleStatusLoader: () => ({
        BackendState: "Running",
        Self: { TailscaleIPs: ["100.101.102.103"] },
      }),
    }),
    { host: "100.101.102.103", network: BridgeNetwork.TAILSCALE },
  );
  assert.throws(
    () =>
      resolveBridgeListenEndpoint({
        environment: { SOMA_BRIDGE_NETWORK: "tailscale" },
        interfaces: { en0: interfaces.en0 },
        tailscaleStatusLoader: () => ({
          BackendState: "Running",
          Self: { TailscaleIPs: ["100.101.102.103"] },
        }),
      }),
    /No active Tailscale IPv4/,
  );
  assert.throws(
    () =>
      resolveBridgeListenEndpoint({
        environment: {
          SOMA_BRIDGE_NETWORK: "tailscale",
          SOMA_BRIDGE_HOST: "192.168.1.42",
        },
        interfaces,
        tailscaleStatusLoader: () => ({
          BackendState: "Running",
          Self: { TailscaleIPs: ["100.101.102.103"] },
        }),
      }),
    /does not match/,
  );
  assert.throws(
    () =>
      resolveBridgeListenEndpoint({
        environment: {
          SOMA_BRIDGE_NETWORK: "lan",
          SOMA_BRIDGE_HOST: "100.101.102.103",
        },
        interfaces,
        tailscaleStatusLoader: () => ({
          BackendState: "Running",
          Self: { TailscaleIPs: ["100.101.102.103"] },
        }),
      }),
    /does not match/,
  );
  assert.throws(
    () =>
      resolveBridgeListenEndpoint({
        environment: { SOMA_BRIDGE_NETWORK: "tailscale" },
        interfaces,
        tailscaleStatusLoader: () => ({
          BackendState: "Stopped",
          Self: { TailscaleIPs: ["100.101.102.103"] },
        }),
      }),
    /not connected/,
  );
  assert.throws(
    () =>
      resolveBridgeListenEndpoint({
        environment: { SOMA_BRIDGE_NETWORK: "tailscale" },
        interfaces,
        tailscaleStatusLoader: () => ({
          BackendState: "Running",
          Self: { TailscaleIPs: ["100.110.120.130"] },
        }),
      }),
    /not owned/,
  );
});
