# Threat model

This threat model covers Soma version 1 on an Android/LightOS device and its
optional, short-lived LAN browser view. It describes implemented boundaries,
not a claim that an unlocked or compromised phone can keep displayed notes
secret.

## Security and privacy goals

Soma aims to:

- keep note, transcript, Important, suggestion, and recording content confidential
  and authenticated at rest;
- capture audio without creating a plaintext temporary or gallery file;
- operate without accounts, cloud services, analytics, advertising, crash
  reporting, update checks, Google Play Services, or outbound network clients;
- make a user-created backup portable, authenticated, and confidential under a
  passphrase;
- expose data to a browser only after an explicit local action, only over the
  current Wi-Fi LAN, only for reading, and only for a short session; and
- fail without losing an already captured recording or silently accepting
  corrupted encrypted data.

Availability against physical destruction, uninstall, forgotten passphrases,
or a destroyed Keystore is not a goal unless the user has a valid exported
backup.

## Assets

The primary assets are note and transcript text, Important items and their source links,
Important suggestions, voice recordings, backup contents and passphrases, Android
Keystore keys, and the temporary browser access code and session token.

Dates, record counts, ordering, ids, state transitions, languages, timestamps,
and audio sizes are sensitive metadata but are not all encrypted in the Room
database. Settings such as language and vibration are ordinary private app
preferences, not secret material.

## Trust boundaries and data flow

1. Compose UI and background transcription handle plaintext inside the Soma
   process.
2. The storage repository encrypts user-authored text before it crosses the
   Room boundary. AES-GCM AAD binds ciphertext to its row, protected field, and
   crypto version.
3. Audio capture flows from `AudioRecord` directly into independently
   authenticated encrypted chunks. Playback and transcription decrypt streams
   in memory.
4. Android Keystore protects separate keys for database text and audio-key
   wrapping. Per-recording data keys are random and distinct.
5. Portable backup export decrypts source audio only in memory, places portable
   WAV bytes inside the passphrase-encrypted outer payload, and never writes a
   plaintext WAV. Import immediately re-encrypts PCM for the destination
   Keystore.
6. In the `browser` flavor, an authenticated request crosses a plain-HTTP LAN
   boundary. The server decrypts only the requested records or audio stream. No
   plaintext browser cache is created by Soma.

## At-rest controls and limitations

Room text BLOBs and audio containers use AES-256-GCM. The production aliases are
`soma_storage_text_v1` and `soma_audio_wrap_v1`; separating them limits accidental
cross-feature key reuse. Audio wrapping attempts StrongBox when available.

GCM authenticates content but does not hide database structure or stop rollback
of an entire valid old database by an attacker with filesystem-level control.
Android app sandboxing and Keystore enforcement are part of the trusted
computing base. A rooted device, malicious OS, accessibility malware, injected
code, or an attacker controlling the unlocked Soma process can read plaintext
as the user can. Soma does not attempt to defend against screenshots, shoulder
surfing, microphone hardware compromise, or memory forensics on a live process.

Android cloud backup and device transfer are disabled in both the application
manifest and data-extraction rules. Audio lives under `noBackupFilesDir`. The
developer demo repository is entirely in memory and does not open Room or audio
files.

## Portable backups

The backup key is derived with PBKDF2-HMAC-SHA256, 600,000 iterations, a fresh
16-byte salt, and a 256-bit result. AES-256-GCM uses a fresh 12-byte IV and
authenticates the complete header. Wrong passphrases and tampering share one
authentication error so the decoder does not expose a separate password oracle.

The 12-character creation minimum is a floor, not an assurance of strength. A
short or predictable phrase remains vulnerable to offline guessing because an
attacker can copy the exported file and try candidates without the phone. Use a
long, unique passphrase and protect the exported file. Soma cannot recover it.

Backup creation necessarily places serialized plaintext, and optional WAV
bytes, in process memory. Mutable buffers and derived keys are cleared where
the JVM permits, but immutable strings and runtime copies cannot be guaranteed
erased. The system document provider chosen for export is outside Soma's trust
boundary once it receives the encrypted backup.

The backup screen asks Android to block screenshots. Restore gives every
imported recording a fresh random file id and stages complete, authenticated
files alongside the live generation before replacing Room rows in one
transaction. A crash before the commit leaves the old data plus harmless
orphans; a crash after it leaves a complete new generation plus harmless old
files. Startup and post-commit cleanup remove unreferenced files. No fragile
cross-filesystem directory swap is used, and a damaged import is never merged
into live rows.

## Offline flavors and experimental cloud boundary

The `browser` and `purist` runtime dependency graphs contain no HTTP client. There is no
OkHttp, Retrofit, Volley, Cronet, Ktor client, Apache HTTP client, analytics SDK,
or crash reporter. The transcription engine and its fixed model are bundled in
the APK; no model or language data is downloaded. WorkManager jobs do not ask
for network connectivity.

The `INTERNET` permission is broad at the Android OS level—it cannot express
“listen only.” In the `browser` flavor, architecture supplies the narrower
guarantee: the isolated LAN module owns a `ServerSocket` and has no client or DNS
code. `tools/check_no_outbound_clients.sh` and Gradle's
`verifyNoHttpClients` task guard the dependency/source boundary. Code review is
still required: a future contributor could add outbound socket code without an
HTTP library.

The `purist` flavor omits the LAN module and the `INTERNET` permission entirely,
which is the strongest choice for users who do not need browser access. Build
tool downloads are development-time traffic and are not app runtime behavior.

The separately installed `cloud` flavor is an explicit exception. Its Developer
settings are off by default. It isolates platform `HttpsURLConnection` calls in
`app/src/cloud`; neither offline flavor compiles that source. Provider API keys
are encrypted with AES-256-GCM under the separate non-exportable Keystore alias
`soma.cloud.credentials.v1`, are never included in backups, and may be deleted
from the screen. Provider requests use the phone's active connection, including
cellular, by default; the optional Wi-Fi-only Developer setting opens provider
connections through Android's concrete Wi-Fi `Network`, preventing a cellular
default route from carrying them. Audio is split locally on silence
for Groq requests; ElevenLabs receives the complete recording so Scribe v2 can
retain language context across pauses. AI Important extraction sends only the new or
edited entry and still creates a suggestion requiring a tap, never an item.

Unfinished Capture and Important-editor drafts are kept out of Android saved
instance state. They are encrypted under the separate non-exportable Keystore
alias `soma.editor.drafts.v1`, written off the UI thread, excluded from exports,
and deleted after the corresponding item is saved.

The optional transcription vocabulary is encrypted under the distinct alias
`soma.transcription.vocabulary.v1`. It is sent with audio when a cloud provider
is selected; an empty list sends nothing. ElevenLabs keyterms add a provider
surcharge, which is disclosed in the editor. ElevenLabs is the initial
accuracy-first provider. Groq customers can enable Zero Data Retention in Groq's
controls; ElevenLabs may retain request content unless the account is eligible
for its Enterprise-only zero-retention mode. Cloud failure falls back to bundled
Whisper and never deletes the encrypted recording.

## Browser view

Browser view is off by default and must be started from its dedicated screen.
The Android bridge accepts only an active interface whose name is Wi-Fi-like and
selects a concrete site-local address. The server then rejects wildcard,
loopback, link-local, and public bind addresses, rejects non-site-local peers,
and validates the exact `Host` value. It never binds to `0.0.0.0`.

The first browser session must submit a random six-digit code displayed on the
phone. A correct code is single-use and creates a 256-bit random session token.
The cookie is `HttpOnly`, `SameSite=Strict`, path-scoped, and limited to the idle
window. It cannot carry `Secure` because the intentionally local endpoint is
plain HTTP. Comparisons use constant-time digest comparison. Five wrong codes
stop the server; starting again creates a new code and token.

Authenticated routes support only days, entries, Important items, and ranged audio
playback. Mutation and export routes do not exist. Pages contain at most five
records. Responses send `Cache-Control: no-store`, a restrictive content
security policy, framing and MIME-sniffing protections, no-referrer policy, and
camera/microphone/geolocation denial. The server does not log note data.

The server stops when the Browser view is disposed, when the activity receives
`ON_STOP`, on explicit stop, after 15 minutes without authenticated activity, or
after five wrong codes. The screen is kept awake and a persistent notification
makes the listener visible while it runs; both are cleared on stop. No background
service is intended to extend the listener beyond the Browser screen's lifetime.

### Accepted LAN trade-off

Plain HTTP gives no transport confidentiality or server authentication. Anyone
able to observe the Wi-Fi can see the access code submission, session cookie,
notes, Important items, and played audio; an active LAN attacker can intercept or replace
traffic. The ephemeral token, narrow bind, short lifetime, read-only routes, and
explicit start reduce exposure but do not make an untrusted café, hotel, or
shared workplace LAN safe. Use Browser view only on a trusted private Wi-Fi
network, stop it when finished, or install the `purist` flavor. Self-signed TLS
is deferred because browser certificate warnings make the intended workflow
hostile and do not by themselves provide a trustworthy identity.

## Capture, transcription, and failure handling

`RECORD_AUDIO` is requested only when recording is first invoked.
`POST_NOTIFICATIONS`, required on current Android targets, is requested only
when the user enables the optional reminder or starts Browser view; denial
leaves that feature off. A voice entry is inserted before recording starts, and
complete audio chunks are synced and authenticated independently. An interrupted
tail can be discarded while prior chunks remain recoverable. The original
encrypted recording remains after transcription failure.

Transcription is local whisper.cpp work, serialized through a mutex and a
single unique WorkManager drain. It runs at background priority, respects the
battery-saver preference, uses leases/retries, and clears sample arrays after
use. Energy-based VAD creates pause-separated chunks so Whisper performs
language detection per utterance. Tiny Q5 is deliberately small and may be
wrong, particularly during dense mid-sentence code-switching; editable text is
the recovery path, not a cloud fallback.

Rule-based Important detection operates on plaintext in process and stores encrypted
suggestions. A match never creates an item without the user's tap. False positives
therefore create a dismissible suggestion, not an automatic commitment.

## Data loss and recovery

Uninstall removes the app database, audio, preferences, and Keystore keys.
Keystore invalidation can likewise make local ciphertext unrecoverable. Android
cloud backup and device transfer will not restore Soma. Export and verify a
portable encrypted backup before uninstalling or moving devices; there is no
account or server-side recovery path.

Deletion and “let go” are local state changes. Flash-storage wear levelling,
SQLite WAL pages, filesystem snapshots, and an already exported backup can
retain older encrypted bytes. Secure physical erasure is delegated to Android
device encryption and device-management controls.
