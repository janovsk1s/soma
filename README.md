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
- Captures inline text or a 16 kHz mono voice note without waiting for sync or
  transcription.
- Keeps encrypted audio playable even if transcription fails.
- Transcribes locally with whisper.cpp and a bundled multilingual tiny Q5_1
  model; pause-based VAD performs language detection per utterance.
- Shows a quiet creation time on every entry. User edits keep the entry in its
  original position, record an encrypted revision, and expose the edit time when
  the entry is opened.
- Suggests possible todos with explicit, testable language rules. A suggestion
  becomes a todo only after a tap.
- Keeps one flat, oldest-first list of open todos. After 30 untouched days, one
  quiet “keep / let go” choice appears when the item is viewed.
- Starts each day with an optional, dismissible summary of open todos and notes
  explicitly marked for return.
- Pages lists in fixed groups of five, matching Paka. Tap `+` for quick entry;
  long-press it for details; long-press a row for deliberate secondary actions.
- Uses Paka's native black-screen palette and exact settings gear. Light mode is
  available only from the hidden Developer screen.
- Offers one optional daily reminder, off by default, and optional vibration.
- Supports English, Latvian, Estonian, Lithuanian, Finnish, Swedish, German, and
  Slovak.
- Exports either a restorable encrypted `.soma` backup or a standard readable
  ZIP containing Markdown, CSV, JSON/JSONL edit history, and optional WAV audio.

Transcription is intentionally modest. The small local model can be inaccurate,
especially during dense mid-sentence language switching. Transcripts remain
editable. The `browser` and `purist` builds never send audio or text elsewhere.

## Privacy by design

- The normal `browser` and `purist` builds have no cloud service, analytics,
  advertising, crash reporting, update checker, Google Play Services dependency,
  outbound HTTP client library, or outbound implementation.
- Note text, transcripts, todo text, rule suggestions, and protected diagnostics
  are encrypted at rest with AES-256-GCM and a non-exportable Android Keystore
  key. Ciphertext is authenticated against its row and field.
- Recordings go directly from `AudioRecord` into crash-recoverable encrypted
  chunks. A separate Keystore key wraps a fresh random key for each recording;
  no plaintext WAV or gallery item is created on disk.
- Android cloud backup and device transfer are disabled. Portable backups are
  encrypted and authenticated offline with a key derived from the user's
  passphrase. Audio is optional and stays plaintext only in memory inside the
  passphrase-encrypted export/import path.
- A readable ZIP is deliberately not encrypted and leaves Soma's trust boundary.
  Store it only somewhere you trust.
- Developer demo data lives only in memory and never opens the real Room or
  audio stores.

The Room database encrypts user-authored content, not every piece of metadata.
Dates, ordering, states, timestamps, ids, relationships, and audio sizes remain
queryable and can be visible to someone who can read the private database. See
the [threat model](docs/THREAT_MODEL.md) for the exact boundary.

Export a backup before uninstalling Soma or moving devices. An invalidated
Android Keystore key leaves only that backup as a recovery path, and Soma cannot
recover a forgotten backup passphrase.

## Permissions

| Permission | Flavor | When and why |
| --- | --- | --- |
| `RECORD_AUDIO` | all | Requested only when the user first starts a recording. |
| `POST_NOTIFICATIONS` | all | Requested only when enabling the optional reminder or starting Browser view. Android 13+ requires it; denial leaves the requested feature off. |
| `INTERNET` | `browser` | Allows the explicitly started inbound LAN HTTP server. No runtime code makes outbound connections. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | `cloud` | Adds Browser view plus opt-in provider requests and Wi-Fi-only enforcement. |

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
  GPT-OSS todo suggestions. It is off by default and requires the user's keys.

Download the chosen APK on the phone, allow installation from that file source
when Android asks, and open the APK to install it. Preview artifacts are signed
with an Android development key and use development package ids, so they do not
establish the signing identity of a future stable release. The two flavors are
separate apps with separate data; install and use one rather than switching
between them. Export a portable encrypted backup before uninstalling a preview.

## Browser view and build flavors

The standard `browser` flavor can serve notes, todos, and authenticated audio to
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
cookie, notes, and audio. The short lifetime, one-time code, ephemeral token, and
read-only routes reduce exposure but do not make an untrusted LAN safe. Use a
trusted private network or install `purist`. Self-signed TLS is deferred because
browser certificate warnings would undermine the intended simple workflow.

## Architecture

| Module | Responsibility |
| --- | --- |
| `app` | Compose UI, lifecycle, reminders, WorkManager transcription drain, demo mode, and flavor bridges |
| `core` | Platform-neutral models, paging, date/policy logic, repositories, and rule-based todo detection |
| `storage` | Room schema/repositories, field encryption, and portable passphrase-encrypted backups |
| `voice` | Direct encrypted recording, interrupted-tail recovery, WAV streaming, and playback |
| `whisper` | Isolated `Transcriber` interface, energy VAD, JNI bridge, vendored whisper.cpp, and bundled model |
| `lanserver` | Dependency-free, inbound-only, read-only HTTP/1.1 server used by the `browser` and `cloud` flavors |

Every runtime graph intentionally contains no third-party HTTP client library.
The `browser`/`purist` source sets contain no outbound implementation. Only the
separate `cloud` source set uses the Android platform HTTPS connection API; API
keys are AES-GCM encrypted under a dedicated Android Keystore key and are never
exported. Local Whisper remains the failure fallback.

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

The provider comparison harness and corpus layout are documented in
[`tools/STT_BENCHMARK.md`](tools/STT_BENCHMARK.md).

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
- [Threat model](docs/THREAT_MODEL.md)
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
