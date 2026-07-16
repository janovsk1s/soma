# Soma Bridge Protocol v1

Protocol v1 uses bounded JSON over TLS 1.3. TLS authenticates the bridge through
a certificate fingerprint delivered out-of-band in the pairing QR. Every
post-pairing request is additionally signed by a device-owned P-256 key.

Canonical request signature input:

```text
SOMA-BRIDGE-REQUEST
PROTOCOL_VERSION
BRIDGE_UUID
CERTIFICATE_SHA256_HEX
METHOD
PATH_WITH_QUERY
BODY_SHA256_HEX
DEVICE_UUID
MONOTONIC_SEQUENCE
UNIX_TIMESTAMP_MILLISECONDS
RANDOM_NONCE
```

The signature is ECDSA P-256/SHA-256 in ASN.1 DER form and Base64 encoded.
The fixed domain, protocol version, bridge UUID, and pinned certificate bind a
signature to one Soma protocol generation and one paired Mac; it cannot be
relayed to another bridge that trusts the same phone key.

Required headers:

- `X-Soma-Device`
- `X-Soma-Sequence`
- `X-Soma-Timestamp`
- `X-Soma-Nonce`
- `X-Soma-Signature`

Pairing is the sole unsigned operation. It requires a one-use 256-bit secret,
expires quickly, and is accepted only over the certificate-pinned connection.
Version 1 accepts literal loopback, RFC1918, and Tailscale `100.64.0.0/10`
addresses. DNS names and public addresses are rejected. A tailnet is only the
route: TLS pinning, device signatures, capability checks, and replay protection
remain mandatory.

Stable capabilities:

- `context.read`
- `codex.thread`
- `codex.turn`
- `codex.stream`
- `proposal.read`
- `proposal.approve`
- `sync.read`
- `sync.write`

Codex-authenticated operations remain Mac-side. Mobile clients never receive or
forward ChatGPT cookies, Codex access tokens, API keys, or `auth.json`.
