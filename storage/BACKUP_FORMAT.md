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
| Payload version | 32-bit integer, currently `1` |
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

## Plaintext payload, version 1

The encrypted payload is deterministic for a given `BackupSnapshot`: values and
lists are written in DTO order with `DataOutputStream` and read with
`DataInputStream`. It starts with its own 32-bit payload version and then contains:

1. export timestamp;
2. daily notes with ordered text/voice entries, audio metadata, and transcription state;
3. todos, including source links, lifecycle timestamps, and stale-review state;
4. todo suggestions, including language, matched rule, and resolution state;
5. per-day “still open” dismissals;
6. transcription queue jobs and sanitized failures;
7. optional portable WAV bytes (inside the authenticated encrypted payload).

Strings use a 32-bit UTF-8 byte length rather than Java modified UTF, so long note
text and all supported languages round-trip exactly. Instants are stored as epoch
seconds plus nanoseconds, and dates as epoch days. Nullable values use a strict
one-byte `0`/`1` marker. Enums are stored by name. Collection and byte lengths are
bounded before allocation.

The v1 in-memory implementation accepts at most a 128 MiB plaintext payload,
100,000 values in any collection, 4 MiB per string, and 128 MiB per individual
portable audio value. The app reserves additional headroom by limiting the sum
of exported WAV data to 120 MiB. Larger archives require a future streaming
container version rather than risking device memory exhaustion.

When audio is included, export streams each device-bound `.sma` container through
authenticated decryption into a WAV byte sequence held only in memory. That WAV is
then protected by this backup's AES-GCM payload. Import immediately encrypts its PCM
under a fresh per-recording key and fresh opaque file id wrapped by the destination
Android Keystore; it never writes the portable WAV to disk. A text-only export has an empty audio-container list
while preserving attachment metadata in voice entries.

## Sensitive-memory handling

The codec accepts passphrases as mutable `CharArray` values. It copies and clears
its internal password, derived-key, serialized-plaintext, and temporary UTF-8
buffers in `finally` blocks where the platform permits. Callers remain responsible
for clearing the `CharArray` they supplied. Immutable domain strings cannot be
reliably erased by the JVM and should not be retained longer than necessary.
