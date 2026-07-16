# Soma Codex bridge security

The bridge is a separate trust boundary from Soma’s existing Browser view.
Browser view intentionally uses short-lived plain HTTP for human-operated local
browsing. Codex context, synchronization, and writable proposals must never use
that transport.

## Architecture

```text
iOS / Android
    ⇅ TLS 1.3, pinned Mac certificate, signed device requests
Soma Bridge on macOS
    ⇅ local stdio only
Codex app-server
```

The Mac keeps ChatGPT/Codex authentication. Mobile clients never receive,
import, or proxy API keys, cookies, refresh tokens, access tokens, or
`~/.codex/auth.json`.

Selected Soma note text does leave the phone: after an explicit first-Ask
disclosure, it travels through the paired Mac to the configured Codex/OpenAI
model service. The iOS sheet shows the exact number of full-text entries before
that first send, and threads are scoped to one selected entry.

## Pairing

1. The bridge creates a private P-256 TLS identity.
2. A ten-minute, single-use 256-bit pairing secret and certificate fingerprint
   are encoded into a QR.
3. The phone pins that fingerprint before sending the secret.
4. The phone creates a non-exportable P-256 signing identity and sends only its
   public key.
5. The bridge stores that public key and a bounded set of capabilities.
6. Five failed secret attempts close the pairing window.

Discovery and the QR’s IP address are routing hints, not identities. A changed
certificate fingerprint fails closed and requires deliberate re-pairing.

## Requests

Every request after pairing signs a deterministic envelope containing method,
path, body hash, device UUID, monotonic sequence, timestamp, and random nonce.
The envelope is also domain-separated by protocol version and bound to the
specific bridge UUID and pinned certificate fingerprint, preventing a request
from being relayed to another paired Mac.
The bridge rejects stale timestamps, repeated sequence numbers, unknown devices,
invalid signatures, public-network peers, oversized inputs, and unsupported
operations. In-process exact retries return their cached response. A private
on-disk ledger stores only request/response hashes and status, never note bodies;
after a crash it fails an ambiguous exact retry closed instead of silently
repeating it. Future writable outboxes still require a durable client
idempotency key and operation-specific recovery before they can be enabled.

The initial implementation grants read-only Codex turns only. Before startup,
the companion compares the installed Codex feature list with a tiny reviewed
allowlist and fails closed if an unreviewed enabled feature appears. Codex runs
with:

- read-only sandboxing;
- network disabled;
- approval policy `never`;
- ephemeral threads and disabled local history persistence;
- shell, unified execution, web search, apps, plugins, MCP servers, hooks,
  memories, and multi-agent tools disabled;
- a stripped subprocess environment that does not forward API keys or unrelated
  application secrets;
- Mac-side account status from `account/read`, without returning account details
  or tokens to the phone;
- no mobile-exposed generic JSON-RPC method;
- a base instruction that treats note content as untrusted data.

Any app-server request for a tool or permission is rejected by the companion.
The bridge grants only the four currently implemented read/Codex capabilities;
reserved proposal and sync capabilities cannot be self-granted during pairing.
Each paired device is limited to one active answer and twenty answer attempts
per hour by default, with a four-answer global ceiling. Completed jobs expire
from memory after thirty minutes and job/thread registries have hard caps.
The client keeps one pinned TLS 1.3 session per pairing, serializes signed
sequences, bounds response bodies, adaptively polls in-progress jobs, and uses
the exact same signed envelope for a safe transport retry. GET responses are
also replay-cached in memory.

## Mutation proposals

Future mutations use structured proposals rather than direct writes. Approval
must bind to the exact proposal body, affected resource IDs, source
fingerprints/revisions, and a short-lived challenge. A stale proposal cannot be
applied. Destructive, shell, network, and external-service actions remain
Mac-only unless a later explicit policy adds biometric mobile approval.

## Storage and revocation

Paired-device records contain public keys and capabilities, use private
filesystem permissions, and never contain note bodies. Threads are ephemeral,
local Codex history persistence is disabled, and Codex job bodies and results
are memory-only in the developer companion. The bridge provides device-list and
revoke commands; revocation immediately rejects new requests. A phone reinstall,
key loss, or changed Mac identity requires re-pairing.

The eventual signed/notarized macOS app should move its TLS private key into the
Data Protection Keychain or Secure Enclave and retain the same wire protocol.

## Remote access

Version 1 can bind one selected RFC1918 interface for local use or one literal
Tailscale `100.64.0.0/10` interface for private remote use. The phone and Mac
must be members of the same tailnet, and tailnet policy should restrict the
bridge's TCP port to the intended phone or user. Tailscale supplies routing and
peer encryption; Soma still requires its own pinned TLS 1.3 certificate, device
signature, capability, sequence, nonce, and response-boundary checks.
Startup confirms the selected CGNAT address is locally assigned and is listed
as this node's address by a running Tailscale daemon; a generic carrier/VPN
`100.64.0.0/10` address is not accepted merely because it shares that range.

Do not expose the bridge with router forwarding, UPnP, a public reverse proxy,
Tailscale Funnel, or the raw app-server WebSocket listener. MagicDNS and public
DNS names are not accepted in pairing codes; the stable literal tailnet address
keeps destination validation deterministic.
