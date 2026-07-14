# Data and backup formats

This document describes Soma's current formats as implemented in the source.
Integers in the binary formats are signed and big-endian unless a field says
otherwise. Lengths are validated before allocation, and unknown format versions
are rejected rather than guessed.

## On-device Room database

The Room database is `soma.db`, schema version 6, using write-ahead logging. Its
exported schemas are checked in under
`storage/schemas/com.soma.storage.db.SomaDatabase/`.

The seven tables are:

- `daily_notes`, with one unique `epoch_day` per note;
- `entries`, ordered by the unique pair `(note_id, position)`;
- `todos`, including `ACTION`, `LIST`, or `EXCERPT` kind and optional source links;
- `todo_suggestions`, including suggested kind, rule, language, and decision state;
- `still_open_dismissals`, keyed by day; and
- `transcription_jobs`, including retry and lease state; and
- `entry_revisions`, containing encrypted previous text for deliberate user edits.

User-authored entry text, todo text, suggestion text and matched rule, and
transcription failure diagnostics are stored as encrypted BLOBs. Structural
metadata needed for queries—dates, ordering, states, ids, timestamps, language
names, Important kinds, audio metadata, deletion tombstones, and relationships—is not encrypted. SQLite page layout,
row counts, and timing therefore remain visible to an attacker who can read the
app's private files. There is no full-text index in version 1; a later search
migration can add one without changing the domain model.

Dates use Java epoch days. Database timestamps use UTC epoch milliseconds.

### Text ciphertext version 1

Production uses a non-exportable 256-bit AES key under Android Keystore alias
`soma_storage_text_v1`. Every encrypted value has this byte layout:

| Field | Encoding |
| --- | --- |
| Format version | 1 byte, currently `1` |
| IV | 12 random bytes |
| Ciphertext and tag | UTF-8 plaintext encrypted with AES-256-GCM, followed by a 16-byte tag |

The GCM additional authenticated data binds the value to its row and column. It
is the sequence below, where each length and the final version are 32-bit
big-endian integers:

```text
len("soma-storage") | "soma-storage"
len(entity_id)      | UTF-8 entity_id
len(field_name)     | UTF-8 field_name
crypto_version
```

Version 1 field names are `entry.text`, `entry.transcriptionFailure`,
`entryRevision.text`, `todo.text`, `suggestion.text`, `suggestion.matchedRule`, and
`transcription.lastFailure`. Moving a ciphertext to another row or protected
field fails GCM authentication.

## Encrypted audio container (`.sma`)

Soma records 16 kHz, mono, signed 16-bit little-endian PCM directly into a
crash-recoverable encrypted container in the app's no-backup directory. It does
not first create a plaintext WAV file. Completed files use `.sma`; an active or
interrupted capture uses `.partial`.

Each recording has a fresh random 256-bit data key. That key is wrapped with a
separate non-exportable Android Keystore key, alias `soma_audio_wrap_v1`. On
devices supporting StrongBox, key creation attempts StrongBox first and falls
back to the regular Android Keystore.

### Header, version 1

The header is written with Java `DataOutputStream`:

| Field | Encoding |
| --- | --- |
| Magic | 8 ASCII bytes: `SOMAUDIO` |
| Version | unsigned byte, currently `1` |
| Audio id | Java `writeUTF` string (2-byte modified-UTF length and data) |
| Sample rate | 32-bit integer, normally `16000` |
| Channels | 16-bit integer, required to be `1` |
| Bits per sample | 16-bit integer, required to be `16` |
| Chunk nonce prefix | 8 random bytes |
| Key-wrap nonce | 12 random bytes |
| Wrapped-key length | 32-bit integer, required to be `48` |
| Wrapped data key | 32 key bytes encrypted with AES-GCM plus a 16-byte tag |

The wrapped-key AAD is the UTF-8 string
`Soma|audio-key|v1|<audio-id>|<rate>|<channels>|<bits>`.

### Authenticated PCM chunks

The remainder is a sequence of independently authenticated chunks:

| Field | Encoding |
| --- | --- |
| Chunk index | 32-bit integer, starting at `0` |
| Plaintext length | 32-bit integer, 1 to 1,048,576 |
| Encrypted length | 32-bit integer, plaintext length plus 16 |
| Encrypted PCM | AES-256-GCM ciphertext and tag |

The nonce is the header's 8-byte prefix followed by the 32-bit chunk index. AAD
is the UTF-8 string
`Soma|audio-chunk|v1|<audio-id>|<rate>|<channels>|<bits>|<index>|<plain-length>`.
Sequential indices and authenticated lengths make reordering, removal, and
substitution detectable.

### Authenticated completion footer

A finalized `.sma` ends with a reserved chunk index of `-1`, a 32-bit encrypted
length, and an AES-GCM value containing the total chunk count and PCM byte count.
It uses the reserved nonce suffix `0xffffffff` and AAD
`Soma|audio-footer|v1|<audio-id>|<rate>|<channels>|<bits>`. Readers require this
footer and exact end-of-file, so deleting any whole number of final chunks is
also detected. Recovery scans a `.partial` file to its last complete,
authenticated chunk, discards only an incomplete tail, writes a fresh footer,
and then finalizes the file.

Playback and LAN serving construct a standard 44-byte WAV header in memory and
stream decrypted PCM after it. No plaintext audio cache is written to disk.
The authenticated audio id must also match the owning attachment and `.sma`
filename, preventing valid containers from being swapped between entries.

## Portable encrypted backup

A portable backup is a binary `SOMABACK` container with MIME type
`application/x-soma-backup`; the UI proposes `Soma-YYYY-MM-DD.soma` as its
filename. This `.soma` suffix is distinct from the internal `.sma` audio format.
Creation requires a passphrase of at least 12 UTF-16 characters. The passphrase
is not stored and cannot be recovered. Every export receives a new salt and IV,
so identical snapshots produce different files.

### Outer container, version 1

| Field | Encoding |
| --- | --- |
| Magic | 8 ASCII bytes: `SOMABACK` |
| Container version | 32-bit integer, currently `1` |
| Payload version | 32-bit integer, currently `7` |
| KDF id | 32-bit length + ASCII `PBKDF2-HMAC-SHA256` |
| PBKDF2 iterations | 32-bit integer, `600000` |
| Derived key size | 32-bit integer, `256` bits |
| Salt | 32-bit length (`16`) + 16 random bytes |
| Cipher id | 32-bit length + ASCII `AES-256-GCM` |
| IV | 32-bit length (`12`) + 12 random bytes |
| Ciphertext length | 32-bit integer, including the 16-byte GCM tag |
| Ciphertext and tag | encrypted payload followed by its GCM tag |

Every byte from the magic through the ciphertext-length field is AES-GCM AAD.
Changing an algorithm id, version, KDF cost, salt, IV, or declared length fails
authentication. Trailing bytes and truncated inputs are rejected. A wrong
passphrase and authenticated-byte corruption intentionally report the same
authentication error.

### Plaintext payload, versions 1 through 7

The encrypted payload is a deterministic `DataOutputStream` serialization in
this order:

1. payload version and export instant;
2. daily notes and their ordered text or voice entries;
3. Important items and optional source links;
4. Important suggestions and their rule/language/decision state;
5. per-day still-open dismissals;
6. transcription jobs and failure state; and
7. optional portable WAV byte sequences;
8. in payload version 2, encrypted-at-source user edit revisions;
9. in payload version 3, the transcription engine and safe fallback category; and
10. in payload version 4, the user-controlled transcription vocabulary; and
11. in payload version 5, the Important kind for items and suggestions;
12. in payload version 6, the optional Important resurface date; and
13. in payload version 7, entry and audio soft-delete tombstones.

Each list begins with a 32-bit count. Strings use a 32-bit byte length followed
by strict UTF-8, not Java modified UTF. Instants use epoch seconds plus
nanoseconds; dates use epoch days. Nullable values have a strict one-byte `0`
or `1` marker, and enums are stored by name.

When audio is included, export authenticates and decrypts each device-bound
`.sma` stream into a standard WAV byte sequence held only in memory. The WAV is
inside the passphrase-encrypted, authenticated outer payload and is never
written as plaintext to disk. Import immediately records its PCM into a fresh
`.sma` container whose new random data key is wrapped by the destination
device's Keystore key. A text-only export leaves the audio list empty while
retaining voice-entry metadata.

The codec clears mutable passphrase copies, derived keys, plaintext serialization
buffers, and temporary byte arrays where the JVM permits. Domain strings are
immutable and cannot be reliably erased from managed memory.

## Readable archive

The optional `Soma-readable-YYYY-MM-DD.zip` export is a standard, deliberately
unencrypted ZIP intended for long-term independence from Soma. It contains:

- `README.txt` and `manifest.json`;
- one `notes/YYYY-MM-DD.md` file per daily note;
- `todos.csv`, including the portable `action`, `list`, or `excerpt` kind;
- `data/notes.json` with exact ids, order, types, timestamps, text, and audio metadata;
- `data/history.jsonl` with every preserved user edit revision; and
- `settings/transcription-vocabulary.txt` with user-provided speech spellings; and
- optional standard 16 kHz mono `audio/*.wav` files.

Structured timestamps are ISO-8601 UTC instants. The archive can be consumed by
ordinary text, spreadsheet, JSON, and audio tools without an app-specific codec.
Readable archive format 6 excludes soft-deleted entries and audio while keeping
the editable transcript and its transcription provenance. The encrypted
portable backup retains tombstones so deleted content can still be restored.
Because it is not encrypted, exporting it moves plaintext outside Soma's trust
boundary. It is not accepted by the restore flow; use `.soma` for restoration.

## Bundled transcription model

`whisper/src/main/assets/ggml-tiny-q5_1.bin` is the multilingual Whisper tiny
Q5_1 model consumed locally by whisper.cpp. Its release pin is:

```text
size:    32152673 bytes
sha256:  818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7
```

The source record is `whisper/src/main/assets/MODEL_INFO.txt`; licensing and
attribution are in `THIRD_PARTY_NOTICES.md`.
