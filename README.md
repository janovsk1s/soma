# Soma

Soma is a calm, offline daily-notes and mind-offloading app for Light Phone III.
The name means “bag” in Latvian: a place to drop a thought, then return to what
still matters. It is a companion to [Paka](https://github.com/janovsk1s/paka) by
the same author and deliberately shares Paka's visual and interaction language.

Soma is a thinking tool, not an archive or engagement product. There are no
accounts, streaks, badges, scores, red dots, analytics, ads, or feeds. The normal
build remains fully local; a separate experimental build can use user-supplied
cloud API keys only when explicitly enabled in Developer settings.

## What it does

- Opens directly to today's single daily note and creates it on first use.
- Opens a focused capture editor when the bottom line or `+` is tapped, and
  starts a 16 kHz mono voice note when the bottom line is deliberately held;
  neither waits for sync or transcription.
- Long-pressing `+` opens a one-shutter photo capture. The JPEG is encrypted
  directly from CameraX memory, appears inline in the daily note, and never
  enters the gallery or a plaintext temporary file.
- After capture, the bottom line offers one calm follow-up: tap to type about
  that photo or hold to attach an encrypted spoken comment. Spoken comments use
  the selected local/cloud transcription engine and remain playable on the photo.
- Keeps encrypted audio playable even if transcription fails.
- Transcribes locally with whisper.cpp and a bundled multilingual tiny Q5_1
  model; pause-based VAD performs language detection per utterance. Local
  decoding stays conservative on Light Phone hardware but automatically uses
  more threads and beam search on more capable Android devices.
- Lets the user maintain a small encrypted transcription vocabulary for names,
  mixed-language words, and uncommon spellings. It prompts local Whisper and
  Groq without an extra fee; ElevenLabs uses it as Scribe v2 keyterms, which
  currently add 20% to that provider's transcription price.
- Shows the engine that actually produced a completed transcript when the voice
  entry is opened, including a quiet reason when a cloud choice fell back to
  local Whisper. This audit trail is also preserved in both export formats.
- Shows a quiet creation time on every entry. User edits keep the entry in its
  original position, record an encrypted revision, and expose the edit time when
  the entry is opened.
- Makes that provenance visible: edit history lists the original, every prior
  wording, and the current wording. Restoring an older version creates another
  revision, so it never destroys the wording it replaces.
- Keeps tags and entry/date relationships in a separate additive metadata
  layer, never inside or on top of authored text. Manual and AI-derived layers
  remain independent and export as portable JSON, Markdown tags, and wikilinks.
- In the experimental cloud build, an independent Developer toggle can derive
  bounded topic tags and explicit date links from each new/edited entry with the
  user's Groq key. It is off by default and never rewrites note text.
- Soft-deletes entries, recordings, and photos with one-tap Undo. Deleted items remain
  recoverable from Settings until the user deliberately chooses delete forever;
  normal notes, transcription, LAN browsing, and readable exports ignore them.
- Long-pressing the bottom input starts voice capture; the bar immediately shows
  starting, elapsed recording time, a square stop action, and encrypted-save
  completion instead of continuing to display the writing hint.
- Suggests possible actions and explicit grocery/list blocks with testable rules
  for all eight languages. A suggestion enters Important only after a tap.
- Lets the user open an entry, select an exact word or phrase with Android's
  native handles, and copy it into Important without moving or changing the
  original note. The copied excerpt keeps its source link.
- Keeps one flat, oldest-first Important section for actions, lists, saved
  excerpts, and locally detected phone/reference numbers. Grocery and ingredient
  headings or bullet points stay grouped as one list. Any Important item can be
  asked to return tomorrow, in one week, or in one month. After 30 untouched days, one
  quiet “keep / let go” choice appears when the item is viewed.
- Starts each day with an optional, dismissible summary of current Important items and notes
  explicitly marked for return.
- Pages Important and settings in fixed groups of five, matching Paka. The daily note
  instead packs its timestamped entries continuously into full pages, breaking
  only at entry boundaries. Tap `+` for quick text entry; long-press it for a photo;
  long-press a row for deliberate secondary actions.
- Uses Paka's native black-screen palette and exact settings gear. Light mode is
  available only from the hidden Developer screen.
- Offers one optional daily reminder, off by default, and optional vibration.
- Supports English, Latvian, Estonian, Lithuanian, Finnish, Swedish, German, and
  Slovak.
- Exports a restorable encrypted `.soma` backup, a complete readable ZIP with
  CSV/JSON/JSONL, or a clean one-way Markdown vault for Obsidian and Logseq.
  The vault keeps daily files, linked Important checklists, earlier wordings,
  and optional standard WAV audio and JPEG originals useful even if Soma no longer exists.

Transcription is intentionally modest. The small local model can be inaccurate,
especially during dense mid-sentence language switching. Transcripts remain
editable. The `browser` and `purist` builds never send audio or text elsewhere.

## Privacy by design

- The normal `browser` and `purist` builds have no cloud service, analytics,
  advertising, crash reporting, update checker, Google Play Services dependency,
  outbound HTTP client library, or outbound implementation.
- Note text, transcripts, Important text, rule suggestions, additive metadata,
  and protected diagnostics are encrypted at rest with AES-256-GCM and a
  non-exportable Android Keystore key. Ciphertext is authenticated against its
  row and field.
- Recordings go directly from `AudioRecord` into crash-recoverable encrypted
  chunks. A separate Keystore key wraps a fresh random key for each recording;
  no plaintext WAV or gallery item is created on disk.
- Camera JPEGs are captured in memory and immediately authenticated into an
  app-private `.smi` container. A separate Keystore wrapping key and a fresh
  per-photo data key isolate images from text and audio encryption.
- Android cloud backup and device transfer are disabled. Portable backups are
  encrypted and authenticated offline with a key derived from the user's
  passphrase. Media is optional and stays plaintext only in memory inside the
  passphrase-encrypted export/import path.
- Readable and Markdown-vault ZIPs are deliberately not encrypted and leave
  Soma's trust boundary. Store them only somewhere you trust; only the encrypted
  `.soma` format can be imported back into the app.
- Developer demo data lives only in memory and never opens the real Room or
  audio or image stores.

The Room database encrypts user-authored content and metadata tag/link values,
not every structural field. Dates, ordering, states, deletion timestamps, ids,
metadata source/timing, other relationships, and media sizes remain queryable
and can be visible to someone who can read the private database. See
the [threat model](docs/THREAT_MODEL.md) for the exact boundary.

Export a backup before uninstalling Soma or moving devices. An invalidated
Android Keystore key leaves only that backup as a recovery path, and Soma cannot
recover a forgotten backup passphrase.

## Permissions

| Permission | Flavor | When and why |
| --- | --- | --- |
| `RECORD_AUDIO` | all | Requested only when the user first starts a recording. |
| `CAMERA` | all | Requested only when the user first deliberately opens photo capture. Photos stay app-private. |
| `POST_NOTIFICATIONS` | all | Requested only when enabling the optional reminder or starting Browser view. Android 13+ requires it; denial leaves the requested feature off. |
| `INTERNET` | `browser` | Allows the explicitly started inbound LAN HTTP server. No runtime code makes outbound connections. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | `cloud` | Adds Browser view plus opt-in provider requests. Cloud requests may use Wi-Fi or cellular; Developer settings can restrict them to Wi-Fi. |

The `purist` flavor has no LAN module and no `INTERNET` permission. The
permission audit script rejects unexpected Android platform permissions (while
ignoring Android's generated, package-local receiver permission). Android's
`INTERNET` permission itself is bidirectional, so the browser flavor's
inbound-only promise is enforced by code structure and dependency checks, not by
the permission system.

## Trying a preview APK

Preview APKs are attached to GitHub prereleases rather than stored in the Git
repository. Choose one flavor:

- `browser` is the normal build and includes the explicitly started LAN Browser
  view.
- `purist` removes the LAN module and the `INTERNET` permission entirely.
- `cloud` is a separate experimental build that can replace the normal build
  while preserving its data when signed with the same key. Developer settings can
  enable Groq Whisper Large v3 or ElevenLabs Scribe v2 transcription and Groq
  GPT-OSS Important suggestions. It is off by default and uses BYOK: the key belongs
  to the user, Soma has no cloud account or proxy, and the provider charges the
  user's provider account directly.
  ElevenLabs is the initial accuracy-first speech choice; Groq remains one tap
  away and can share its key with AI Important extraction.
  Choosing one speech language sends that language to the provider; choosing
  several keeps pause-separated language auto-detection. A provider result outside
  the selected set, a missing key, an enabled Wi-Fi-only restriction, or any API
  failure falls back to bundled local Whisper. By default provider requests work
  on both Wi-Fi and cellular. Open a completed voice entry to see which engine actually
  produced it and whether fallback occurred. AI is consulted only when the local
  Important rules find no candidate, and every result is still an optional inline
  suggestion.
  A completed voice entry can be deliberately retranscribed from its long-press
  options; the existing audio and text remain recoverable throughout the retry.
  Transcription vocabulary is omitted from provider requests when empty. When
  present it is sent with audio to the selected provider; ElevenLabs documents
  a 20% keyterm surcharge, which Soma also states in the vocabulary editor.

Download the chosen APK on the phone, allow installation from that file source
when Android asks, and open the APK to install it. Preview artifacts are signed
with an Android development key and use development package ids, so they do not
establish the signing identity of a future stable release. The preview flavors are
separate apps with separate data; install and use one rather than switching
between them. Export a portable encrypted backup before uninstalling a preview.

## Browser view and build flavors

The standard `browser` flavor can serve notes, Important items, authenticated audio, and photos to
a browser on the same trusted Wi-Fi network. It is off by default. Starting it
selects a concrete Wi-Fi site-local address—never a wildcard, loopback, mobile,
or public address—and shows a URL plus a single-use six-digit code. A successful
login receives a random 256-bit session cookie. Five wrong codes stop the server.

The HTTP surface is read-only: there are no edit, delete, backup, or export
routes. It shows five records per page, decrypts data per request, uses `no-store`
responses, and never writes a plaintext cache. A persistent notification is
shown while the listener runs. Leaving the screen, backgrounding the app,
explicitly stopping, or 15 minutes without authenticated activity closes it.

Browser view uses plain HTTP. Anyone able to observe or actively interfere with
that Wi-Fi can read or alter the session's traffic, including the access code,
cookie, notes, audio, and photos. The short lifetime, one-time code, ephemeral token, and
read-only routes reduce exposure but do not make an untrusted LAN safe. Use a
trusted private network or install `purist`. Self-signed TLS is deferred because
browser certificate warnings would undermine the intended simple workflow.

## Architecture

| Module | Responsibility |
| --- | --- |
| `app` | Compose UI, lifecycle, reminders, WorkManager transcription drain, demo mode, and flavor bridges |
| `core` | Platform-neutral models, paging, date/policy logic, repositories, and rule-based Important detection |
| `storage` | Room schema/repositories, field encryption, and portable passphrase-encrypted backups |
| `voice` | Direct encrypted recording, interrupted-tail recovery, WAV streaming, and playback |
| `media` | Per-photo Keystore wrapping, authenticated image containers, and in-memory JPEG recovery |
| `whisper` | Isolated `Transcriber` interface, energy VAD, JNI bridge, vendored whisper.cpp, and bundled model |
| `lanserver` | Dependency-free, inbound-only, read-only HTTP/1.1 server used by the `browser` and `cloud` flavors |

Every runtime graph intentionally contains no third-party HTTP client library.
The `browser`/`purist` source sets contain no outbound implementation. Only the
separate `cloud` source set uses the Android platform HTTPS connection API; API
keys are AES-GCM encrypted under a dedicated Android Keystore key and are never
exported. Transcription vocabulary uses a different Keystore key and is included
in portable and readable exports. Local Whisper remains the failure fallback.
Groq `openai/gpt-oss-20b` performs optional structured Important and metadata
extraction; each feature has its own off-by-default Developer toggle.

## Building and verification

Soma requires JDK 17, the Android SDK declared by the app (`compileSdk 37`,
`minSdk 26`, `targetSdk 36`), and the Android NDK/CMake toolchain for whisper.cpp.
Release builds currently target Light Phone III's `arm64-v8a` ABI.

```sh
./gradlew test lint assembleDebug assembleRelease
tools/check_no_outbound_clients.sh
tools/check_apk_permissions.sh browser app/build/outputs/apk/browser/debug/app-browser-debug.apk
tools/check_apk_permissions.sh purist app/build/outputs/apk/purist/debug/app-purist-debug.apk
tools/check_apk_permissions.sh cloud app/build/outputs/apk/cloud/debug/app-cloud-debug.apk
```

The installable Light Phone preview keeps the debug application id/signing key,
packages only the device's ABI, and enables release shrinking:

```sh
./gradlew :app:assembleCloudPreview
```

The provider comparison harness and corpus layout are documented in
[`tools/STT_BENCHMARK.md`](tools/STT_BENCHMARK.md).
Capture latency, idle-work, transcription, memory, and APK-size budgets are
documented in [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md). They are measured on
real Light Phone hardware before a stable release; unit tests also bound local
detection input and result counts so unusually large entries cannot create
unbounded suggestion work.

`check_no_outbound_clients.sh` audits both flavor runtime graphs and first-party
source for known client APIs, Google service SDKs, analytics, and reporting
libraries. `check_apk_permissions.sh` inspects the merged APK manifest with
Android build tools; it intentionally fails if a dependency introduces another
permission. Gradle's `check` lifecycle also runs `verifyNoHttpClients`.

The bundled model is 32,152,673 bytes and is pinned by SHA-256:

```text
818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7
```

Its source record is `whisper/src/main/assets/MODEL_INFO.txt`. whisper.cpp 1.9.1,
ggml, and the OpenAI Whisper model weights are MIT-licensed; see
[third-party notices](THIRD_PARTY_NOTICES.md).

## Compatibility and independence

Soma is an independent, unofficial community project designed for Light Phone
III. It is not affiliated with, endorsed by, sponsored by, or published by The
Light Phone, Inc.

“Light Phone,” “Light Phone III,” “LightOS,” and related marks belong to their
owner and are used only to describe compatibility. Soma contains no LightOS
source code, Light branding, or proprietary Light assets.

## Documentation

- [Data and backup formats](docs/FORMATS.md)
- [Performance and battery contract](docs/PERFORMANCE.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Light Phone III tool readiness](docs/LIGHT_TOOL.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Acknowledgments](ACKNOWLEDGMENTS.md)

## License

Copyright © 2026 Adrians Janovskis
([@janovsk1s](https://github.com/janovsk1s)).

Soma is licensed under [GPL-3.0-only](LICENSE), with the attribution and branding
terms in [ADDITIONAL_TERMS.md](ADDITIONAL_TERMS.md). See [NOTICE](NOTICE),
[ACKNOWLEDGMENTS.md](ACKNOWLEDGMENTS.md), and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for authorship, AI-assistance,
and dependency information.
