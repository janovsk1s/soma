# Soma Codex Bridge for macOS

This companion keeps Codex authentication on the Mac and exposes a deliberately
small Soma-specific API to paired phones. It does **not** expose the Codex
app-server WebSocket port to the LAN.

Requirements:

- macOS with the Codex CLI installed and signed in;
- Node.js 20 or newer;
- the phone and Mac on the same trusted network, or signed into the same
  Tailscale tailnet.

Run:

```sh
cd bridge/macos
npm install
npm start
```

For private remote access, install Tailscale on the Mac and phone, connect both
to the same tailnet, then run:

```sh
npm run start:tailscale
```

This binds directly to the Mac's stable Tailscale `100.x` address. It does not
use Tailscale Funnel, open a public route, or replace Soma's pinned TLS and
signed device requests. The same tailnet address also works while both devices
are on the same Wi-Fi. Re-pair once using the tailnet QR if an older pairing
contains a LAN address.

To verify the installed Codex app-server accepts Soma’s locked-down policy
without sending a model turn:

```sh
npm run check:codex
```

On first launch the bridge:

1. creates a private TLS identity under
   `~/Library/Application Support/SomaBridge`;
2. starts Codex app-server over local stdio;
3. opens a ten-minute, one-use pairing window;
4. prints a `soma://pair` URI and writes a QR PNG beside its private state.

The phone pins the TLS certificate fingerprint contained in that QR, creates
its own P-256 signing key, and signs every later request. Pairing secrets and
Codex credentials are never stored on the phone.

When the user approves an Ask, full text from the selected Soma entry and its
connected entries is sent through this companion to the configured Codex/OpenAI
model service. The mobile UI discloses the exact entry count before the first
send; credentials still remain Mac-side.

Environment variables:

- `SOMA_BRIDGE_PORT` — HTTPS port, default `8843`;
- `SOMA_BRIDGE_NETWORK` — `lan` (default) or `tailscale`;
- `SOMA_BRIDGE_HOST` — exact loopback, RFC1918, or Tailscale IPv4 interface;
  otherwise the companion selects an interface matching the network mode;
- `TAILSCALE_BIN` — optional explicit path to the Tailscale CLI;
- `SOMA_BRIDGE_HOME` — private state directory;
- `CODEX_BIN` — path to the Codex executable;
- `SOMA_BRIDGE_MODEL` — optional Codex model override;
- `SOMA_BRIDGE_PAIRING_SECONDS` — pairing-window length, default `600`;
- `SOMA_BRIDGE_ACTIVE_TURNS_PER_DEVICE` — active-answer ceiling, default `1`;
- `SOMA_BRIDGE_ACTIVE_TURNS_GLOBAL` — global active-answer ceiling, default `4`;
- `SOMA_BRIDGE_TURNS_PER_HOUR` — per-device hourly attempt ceiling, default `20`.

This is a developer companion, not yet a signed/notarized `.app`. Its protocol
and security boundaries are intended to remain stable while the macOS shell is
packaged more formally.

The companion starts app-server with ephemeral history and a deliberately
minimal tool surface: shell execution, web search, apps, plugins, MCP servers,
hooks, memories, and multi-agent tools are disabled. Pairing currently grants
only selected-context read access and Codex thread/turn streaming. Startup also
audits the installed Codex feature list and fails closed when a new enabled
feature has not been reviewed. Turn concurrency, hourly attempts, completed-job
retention, and live thread registries are bounded to limit model spend and
memory use if a paired device is compromised.

For defense in depth, restrict TCP port `8843` in the tailnet policy to the
phone/user that should reach the Mac. Do not enable Funnel for this port.
Tailscale mode verifies that the chosen `100.x` address is both locally
assigned and reported by a running local Tailscale daemon before binding.
