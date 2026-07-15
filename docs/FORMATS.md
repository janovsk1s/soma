# Data and backup formats

This document describes Soma's current formats as implemented in the source.
Integers in the binary formats are signed and big-endian unless a field says
otherwise. Lengths are validated before allocation, and unknown format versions
are rejected rather than guessed.

## On-device Room database

The Room database is `soma.db`, schema version 9, using write-ahead logging. Its
exported schemas are checked in under
`storage/schemas/com.soma.storage.db.SomaDatabase/`.

The ten tables are:

- `daily_notes`, with one unique `epoch_day` per note;
- `entries`, ordered by the unique pair `(note_id, position)`;
- `todos`, including `ACTION`, `LIST`, or `EXCERPT` kind and optional source links;
- `todo_suggestions`, including suggested kind, rule, language, and decision state;
- `still_open_dismissals`, keyed by day; and
- `transcription_jobs`, including retry and lease state; and
- `entry_revisions`, containing encrypted previous text for deliberate user edits; and
- `entry_metadata`, containing independently replaceable manual, LOCAL, or AI layers
  whose tags and links are encrypted;
- `tracking_logs`, containing queryable type/time/source fields and one encrypted
  structured payload for each meal, recipe, workout, or receipt; and
- `tracking_log_revisions`, containing encrypted full snapshots of every prior
  structured-log version.

User-authored entry text, todo text, suggestion text and matched rule,
transcription failure diagnostics, and entry-metadata tags/link values are
stored as encrypted BLOBs. Structural
metadata needed for queries—dates, ordering, states, ids, timestamps, language
names, Important kinds, media metadata, deletion tombstones, metadata source,
and other relationships—is not encrypted. SQLite page layout,
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
`entryRevision.text`, `entryMetadata.tags`, `entryMetadata.links`, `todo.text`,
`suggestion.text`, `suggestion.matchedRule`, `transcription.lastFailure`,
`trackingLog.payload`, and `trackingLogRevision.payload`.
Moving a ciphertext to another row or protected
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

## Encrypted image container (`.smi`)

CameraX returns a bounded JPEG buffer in memory. Soma encrypts it directly into
an app-private `.smi` file in the no-backup directory and never creates a
plaintext temporary file or MediaStore/gallery item. Every image has a fresh
random 256-bit data key, wrapped by the separate non-exportable Android
Keystore alias `soma_image_wrap_v1` (StrongBox is attempted when available).

The version 1 container is written with `DataOutputStream`:

| Field | Encoding |
| --- | --- |
| Magic | 4 ASCII bytes: `SMIG` |
| Version | unsigned byte, currently `1` |
| Image id | Java `writeUTF` string |
| Width, height | two positive 32-bit integers |
| Display rotation | 32-bit integer: `0`, `90`, `180`, or `270` |
| Plain JPEG length | positive 32-bit integer, capped at 24 MiB |
| Key-wrap nonce | unsigned-byte length followed by nonce bytes |
| Wrapped-key length and key | 32-bit length followed by AES-GCM ciphertext/tag |
| Data nonce | unsigned-byte length followed by nonce bytes |
| Ciphertext length and JPEG | 32-bit length followed by AES-GCM ciphertext/tag |

Both the wrapped key and image ciphertext authenticate the version, dimensions,
rotation, plaintext length, and UTF-8 image id. Readers require the attachment
id and exact end-of-file to match. Display, export, and LAN serving decrypt into
bounded memory only and never create a plaintext disk cache.

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
| Payload version | 32-bit integer, currently `12` |
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

### Plaintext payload, versions 1 through 12

The encrypted payload is a deterministic `DataOutputStream` serialization in
this order:

1. payload version and export instant;
2. daily notes and their ordered text, voice, or image entries (an image entry
   may also carry one encrypted spoken comment and its editable transcript);
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
13. in payload version 7, entry and audio soft-delete tombstones; and
14. in payload version 8, image attachments, image tombstones, and optional
    portable JPEG byte sequences; and
15. in payload version 9, additive entry metadata layers with normalized tags
    and typed entry, date, or tag links; and
16. in payload version 10, current meal, recipe, and workout logs plus every
    prior log snapshot; and
17. in payload version 11, receipt logs with merchant, currency, exact minor-unit
    totals, tax, purchased items, quantities, prices, and optional categories; and
18. payload version 12 changes no structure: it marks the introduction of the
    `LOCAL_WHISPER_BASE` transcription engine name inside provenance. Enums are
    stored by name and rejected when unknown, so the version bump is the
    compatibility story — an older build refuses a newer backup with a clear
    version message instead of failing on an unfamiliar engine name.

Each list begins with a 32-bit count. Strings use a 32-bit byte length followed
by strict UTF-8, not Java modified UTF. Instants use epoch seconds plus
nanoseconds; dates use epoch days. Nullable values have a strict one-byte `0`
or `1` marker, and enums are stored by name.

When media is included, export authenticates and decrypts each device-bound
`.sma` or `.smi` container into standard WAV or JPEG bytes held only in memory. The media is
inside the passphrase-encrypted, authenticated outer payload and is never
written as plaintext to disk. Import immediately records its PCM into a fresh
`.sma` container whose new random data key is wrapped by the destination
device's Keystore key. A text-only export leaves both media lists empty; restore
keeps available text/captions and replaces missing attachments with an explicit
plain-text placeholder.

The codec clears mutable passphrase copies, derived keys, plaintext serialization
buffers, and temporary byte arrays where the JVM permits. Domain strings are
immutable and cannot be reliably erased from managed memory.

Committed restore fixtures pin this format across releases:
`storage/src/test/resources/fixtures/portable-backup-v11.somabackup` and
`portable-backup-v12.somabackup` decode in `PortableBackupFixtureTest` with the
passphrase documented there. When the payload version advances, keep the old
fixtures and add a new one so `SUPPORTED_PAYLOAD_VERSIONS` remains an enforced
promise rather than a comment.

## Readable archive

The optional `Soma-readable-YYYY-MM-DD.zip` export is a standard, deliberately
unencrypted ZIP intended for long-term independence from Soma. It contains:

- `README.txt` and `manifest.json`;
- one `notes/YYYY-MM-DD.md` file per daily note;
- `todos.csv`, including the portable `action`, `list`, or `excerpt` kind;
- `logs.csv`, with spreadsheet-friendly meal, recipe, workout, and receipt rows;
- `data/notes.json` with exact ids, order, types, timestamps, text, and media metadata;
- `data/history.jsonl` with every preserved user edit revision; and
- `data/metadata.json` with manual, LOCAL, and AI metadata layers, tags, and typed links;
- `data/logs.json` with complete food quantities, nutrition provenance, workout
  sets, receipt merchants, exact totals, and purchased items;
- `data/log-history.jsonl` with every earlier structured-log snapshot;
- `settings/transcription-vocabulary.txt` with user-provided speech spellings; and
- optional standard 16 kHz mono `audio/*.wav` and `images/*.jpg` files.

Structured timestamps are ISO-8601 UTC instants. The archive can be consumed by
ordinary text, spreadsheet, JSON, image, and audio tools without an app-specific codec.
Readable archive format 10 excludes soft-deleted entries and media while keeping
the editable transcript and its transcription provenance. The encrypted
portable backup retains tombstones so deleted content can still be restored.
Because it is not encrypted, exporting it moves plaintext outside Soma's trust
boundary. It is not accepted by the restore flow; use `.soma` for restoration.

## Markdown vault

The optional `Soma-vault-YYYY-MM-DD.zip` export is a standard, deliberately
unencrypted, one-way Markdown vault. It is intended for Obsidian, Logseq, plain
text editors, and long-term use without Soma. Format version 5 contains:

- `README.md` with the portability, privacy, and one-way-export contract;
- `.soma/manifest.json` with format version, export instant, time zone, and
  record counts;
- one root-level `YYYY-MM-DD.md` file per daily note;
- `Important.md`, with open, done, and let-go items as Markdown checklists;
- `Logs.md`, with meals, recipes, workouts, receipts, purchased items, exact
  totals, quantities, nutrition sources, and sets;
- one `history/YYYY-MM-DD-<token>.md` file for each edited entry; and
- one `history/log-<token>.md` file for each edited structured log; and
- optional standard WAV and JPEG files under `media/`, embedded from the owning day.

Daily-file YAML frontmatter contains `date`, `created`, `last_edited`, `tags`,
and `soma_timezone`. `created` and `last_edited` are ISO-8601 UTC instants;
visible entry headings use the device time zone recorded by `soma_timezone`.
`tags` contains the distinct manual and derived tags attached to visible entries
for that day. Entry metadata also renders as portable `#tags`, date wikilinks,
and stable links to related entry blocks.

Every visible entry receives a stable Obsidian block anchor. `Important.md`
links source-backed items to that exact block, and edited entries link to their
history file. File paths and anchors use a truncated SHA-256 token rather than
the raw entry id, so legacy or imported ids cannot inject paths. ZIP members are
written in deterministic order with fixed member timestamps.

Format version 4 excludes entry and media tombstones. It retains current text,
editable voice transcripts, all earlier wordings, Important state/kind/source,
show-again dates, entry metadata, and optional playable audio and viewable
photos. No API keys or encrypted
credential stores are part of a backup snapshot, so they cannot enter the
vault. The vault is not accepted by the restore flow; use `.soma` for a
restorable backup.

Browser view can generate this same text-only vault when Data export was enabled
for that single LAN session. The browser route never includes WAV or JPEG media,
requires the authenticated session and an explicit GET, and sends the ZIP directly
from memory with `no-store`. It remains a deliberately unencrypted one-way export;
the LAN confirmation lists logs and complete edit history before download.

## Workbook text

A workbook is a guided journaling programme the user imports by pasting plain
text into the Browser view's `/workbook` page. The format is deliberately
tiny and parsed by `core`'s `Workbook.parse`, which rejects anything it does
not recognise rather than importing garbage:

```text
# <title>                    exactly once, first non-blank line
## <section heading>         one per day; document order is programme order
> <quote>                    optional, at most one per section
- <question>                 zero or more per section
* <exercise>                 optional, at most one per section
```

Blank lines are ignored; any other non-blank line fails the import. Caps:
64 sections, headings ≤ 120 characters, ≤ 12 prompt lines per section, lines
≤ 300 characters, whole text ≤ 64 KiB. The practical paste ceiling is lower:
the urlencoded LAN form is capped at 64 KiB and non-ASCII characters inflate
under urlencoding, so a realistic workbook (tens of KB) fits with headroom.

An answer is an ordinary entry for today plus a `MANUAL` metadata layer whose
tag and `TAG` link target is `wb-<slug>-<NN>` with relation `workbook`, where
`<slug>` derives from the title (42 characters at most, so the widest tag is
exactly the 48-character tag limit). Progress is never stored — the next
unanswered section is derived from those links, so it survives backup restore.
Re-importing a workbook with the same title resumes at the same position; a
retitled import gets a new slug, restarts at day one, and old `wb-…` links
remain as ordinary tags in insights and the graph.

The workbook text itself is stored encrypted at rest in an Android
SharedPreferences value under the dedicated Keystore alias `soma.workbook.v1`
(AES-256-GCM, 12-byte IV prefix — the same layout as the transcription
vocabulary). It is deliberately **not** part of portable backups: like model
weights, it is re-importable third-party content, while the answers and their
links are user data and are in every backup.

## Bundled transcription model

`whisper/src/main/assets/ggml-tiny-q5_1.bin` is the multilingual Whisper tiny
Q5_1 model consumed locally by whisper.cpp. Its release pin is:

```text
size:    32152673 bytes
sha256:  818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7
```

The source record is `whisper/src/main/assets/MODEL_INFO.txt`; licensing and
attribution are in `THIRD_PARTY_NOTICES.md`.
