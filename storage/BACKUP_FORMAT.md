# Soma portable backup format

Soma backups are offline, passphrase-encrypted binary files. Version 1 uses only
JDK/Android cryptographic and data-stream primitives; it has no JSON or network
dependency.

## Outer container, version 1

All integers are signed, big-endian values written by `DataOutputStream`. Text in
the header is length-prefixed US-ASCII.

| Field | Encoding |
| --- | --- |
| Magic | 8 bytes: `SOMABACK` |
| Container version | 32-bit integer, currently `1` |
| Payload version | 32-bit integer, currently `9` |
| KDF id | 32-bit byte length + `PBKDF2-HMAC-SHA256` |
| PBKDF2 iterations | 32-bit integer, `600000` |
| Derived key size | 32-bit integer, `256` bits |
| Salt | 32-bit length (`16`) + 16 random bytes |
| Cipher id | 32-bit byte length + `AES-256-GCM` |
| IV | 32-bit length (`12`) + 12 random bytes |
| Ciphertext length | 32-bit integer, including the 16-byte GCM tag |
| Ciphertext | Encrypted payload followed by its GCM tag |

Every byte through and including `ciphertext length` is supplied to AES-GCM as
additional authenticated data. Consequently, changing the version, algorithms,
KDF cost, salt, IV, or declared encrypted length invalidates authentication.
Trailing bytes and truncated inputs are rejected.

The 256-bit AES key is derived directly from the user passphrase and random salt
using PBKDF2-HMAC-SHA256 with 600,000 iterations. A new backup passphrase must be
at least 12 UTF-16 characters. Salt and IV are generated anew for every export,
so two exports of identical data intentionally have different outer bytes.

Wrong passphrases and authenticated-byte corruption produce the same
`BackupAuthenticationException`. This avoids exposing a password-verification
oracle.

## Plaintext payload, versions 1 through 9

The encrypted payload is deterministic for a given `BackupSnapshot`: values and
lists are written in DTO order with `DataOutputStream` and read with
`DataInputStream`. It starts with its own 32-bit payload version and then contains:

1. export timestamp;
2. daily notes with ordered text, voice, or image entries, media metadata,
   transcription state, and deletion tombstones;
3. todos, including source links, lifecycle timestamps, and stale-review state;
4. todo suggestions, including language, matched rule, and resolution state;
5. per-day “still open” dismissals;
6. transcription queue jobs and sanitized failures;
7. optional portable WAV bytes (inside the authenticated encrypted payload);
8. version-gated edit revisions, transcription provenance, vocabulary,
   Important kind/resurface state, and image attachments/JPEG bytes; and
9. in payload version 9, manual and AI entry-metadata layers with tags and typed
   links.

Strings use a 32-bit UTF-8 byte length rather than Java modified UTF, so long note
text and all supported languages round-trip exactly. Instants are stored as epoch
seconds plus nanoseconds, and dates as epoch days. Nullable values use a strict
one-byte `0`/`1` marker. Enums are stored by name. Collection and byte lengths are
bounded before allocation.

The current in-memory implementation accepts at most a 128 MiB plaintext payload,
100,000 values in any collection, 4 MiB per string, and 128 MiB per individual
portable media value. The app reserves additional headroom for exported media.
Larger archives require a future streaming
container version rather than risking device memory exhaustion.

When media is included, export authenticates each device-bound `.sma` or `.smi`
container into a standard WAV or JPEG byte sequence held only in memory. Those
bytes are protected by the backup's AES-GCM payload. Import immediately creates
fresh destination containers, opaque ids, and wrapped data keys; it never writes
portable WAV or JPEG bytes to a plaintext disk cache. A text-only export keeps
the authored text/captions while omitting portable media byte sequences.

The detailed, canonical field order for every payload generation is documented
in [`../docs/FORMATS.md`](../docs/FORMATS.md).

## Sensitive-memory handling

The codec accepts passphrases as mutable `CharArray` values. It copies and clears
its internal password, derived-key, serialized-plaintext, and temporary UTF-8
buffers in `finally` blocks where the platform permits. Callers remain responsible
for clearing the `CharArray` they supplied. Immutable domain strings cannot be
reliably erased by the JVM and should not be retained longer than necessary.
