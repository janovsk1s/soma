# Threat model

This threat model covers Soma version 1 on an Android/LightOS device and its
optional, short-lived LAN browser view. It describes implemented boundaries,
not a claim that an unlocked or compromised phone can keep displayed notes
secret.

## Security and privacy goals

Soma aims to:

- keep note, transcript, Important, suggestion, and recording content confidential
  and authenticated at rest;
- capture audio or photos without creating a plaintext temporary or gallery file;
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
Important suggestions, additive metadata tags and links, voice recordings,
revisioned meal/recipe/workout/receipt logs, photos, backup contents and passphrases, Android Keystore keys, and the temporary
browser access code and session token.

Dates, record counts, ordering, ids, state transitions, languages, timestamps,
metadata source/derivation time, and media sizes are sensitive metadata but are
not all encrypted in the Room database. Metadata tags and link values are
encrypted. Settings such as language and vibration are ordinary private app
preferences, not secret material.

## Trust boundaries and data flow

1. Compose UI and background transcription handle plaintext inside the Soma
   process.
2. The storage repository encrypts user-authored text, additive metadata, and
   structured tracking payloads before they cross the Room boundary. AES-GCM AAD binds ciphertext to
   its row, protected field, source layer, and crypto version.
3. Audio capture flows from `AudioRecord` directly into independently
   authenticated encrypted chunks. Playback and transcription decrypt streams
   in memory.
4. Android Keystore protects separate keys for database text, audio-key
   wrapping, and image-key wrapping. Per-recording and per-image data keys are
   random and distinct.
5. Portable backup export decrypts source media only in memory, places portable
   WAV bytes inside the passphrase-encrypted outer payload, and never writes a
   plaintext WAV. Import immediately re-encrypts PCM for the destination
   Keystore.
6. In the `browser` flavor, an authenticated request crosses a plain-HTTP LAN
   boundary. The server decrypts only the requested records or audio stream. No
   plaintext browser cache is created by Soma.

## At-rest controls and limitations

Room text and metadata BLOBs, audio containers, and image containers use AES-256-GCM. The
production aliases are `soma_storage_text_v1`, `soma_audio_wrap_v1`, and
`soma_image_wrap_v1`; separating them limits accidental
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
imported recording and image a fresh random file id and stages complete, authenticated
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
The independent automatic-metadata toggle sends the same bounded entry text to
Groq `openai/gpt-oss-20b` and accepts only normalized topic tags and explicit
ISO date links. A third independent tracking-proposal toggle sends a deliberately
selected entry's bounded text and, when present, its JPEG to Groq. Text proposals
use strict structured output; photo proposals use the replaceable preview vision
model and remain editable suggestions. They never mutate the source entry or
become a log without a user Save. All analysis features are off by default.

Unfinished Capture and Important-editor drafts are kept out of Android saved
instance state. They are encrypted under the separate non-exportable Keystore
alias `soma.editor.drafts.v1`, written off the UI thread, excluded from exports,
and deleted after the corresponding item is saved.

The optional transcription vocabulary is encrypted under the distinct alias
`soma.transcription.vocabulary.v1`. It is sent with audio when a cloud provider
is selected; an empty list sends nothing. ElevenLabs keyterms add a provider
surcharge, which is disclosed in the editor. Groq Turbo is the initial low-cost
cloud provider; Groq Large v3 and ElevenLabs are explicit accuracy choices. Groq
processing and any retained customer data are located in the United States.
Groq customers can enable Zero Data Retention in Groq's controls; ElevenLabs may retain request content unless the account is eligible
for its Enterprise-only zero-retention mode. Cloud failure falls back to bundled
Whisper and never deletes the encrypted recording.

## Browser view

Browser view is off by default and must be started from its dedicated screen.
The Android bridge accepts only an active interface whose name is Wi-Fi-like and
selects a concrete private address: IPv4 site-local (RFC 1918) or IPv6
unique-local (fc00::/7), so IPv6-only Wi-Fi networks work too. The server then
rejects wildcard, loopback, link-local, and public bind addresses, rejects
peers outside those private ranges, and validates the exact `Host` value. It
never binds to `0.0.0.0`. The bridge
prefers fixed port `8787` for a repeatable bookmark and falls back to an ephemeral
port only after a bind failure. A fixed port does not broaden the accepted
interfaces or peers, and the Wi-Fi address can still be reassigned by the router.

The first browser session must submit a random six-digit code displayed on the
phone. A correct code is single-use and creates a 256-bit random session token.
The cookie is `HttpOnly`, `SameSite=Strict`, path-scoped, and limited to the idle
window. It cannot carry `Secure` because the intentionally local endpoint is
plain HTTP. Comparisons use constant-time digest comparison. Five wrong codes
stop the server; starting again creates a new code and token.

Authenticated routes support days, entries, Important items, confirmed meal,
recipe, workout and receipt logs, local metadata insights and their static connection
graph, ranged audio, and image playback. Metadata owned by a tombstoned entry
and entry links to a tombstoned target are omitted. Mutation routes do not exist.
An export route exists only when the user enables the ephemeral Data export control
before starting that LAN session. It is GET-only, single-flight, text-only, and
returns the existing plaintext Markdown vault after a localized confirmation names
notes, Important items, logs, metadata, and complete edit history. HEAD cannot build
the archive, and its response buffer is wiped when the connection closes. Stopping
the listener or leaving the screen removes export authority. Pages contain at most
five record or connection rows. Responses send
`Cache-Control: no-store`, a restrictive content
security policy, framing and MIME-sniffing protections, no-referrer policy, and
camera/microphone/geolocation denial. The server does not log note data.

The only route available before authentication besides the access-code form is
`/assets/forest.webp`. It returns one of eight application-bundled monochrome
landscapes fixed for the server session. These files are immutable release assets,
contain no user data, and require no outbound request. All notes, logs, metadata,
media, and export routes remain authenticated.

The server stops when the Browser view is disposed, when the activity receives
`ON_STOP`, on explicit stop, after 15 minutes without authenticated activity, or
after five wrong codes. The screen is kept awake and a persistent notification
makes the listener visible while it runs; both are cleared on stop. No background
service is intended to extend the listener beyond the Browser screen's lifetime.

### Accepted LAN trade-off

Plain HTTP gives no transport confidentiality or server authentication. Anyone
able to observe the Wi-Fi can see the access code submission, session cookie,
notes, Important items, metadata insights/graph, played media, and an enabled
plaintext vault download; an active LAN attacker can intercept or replace traffic.
The ephemeral token, narrow bind, short lifetime, no-mutation routes, explicit start,
and session-only export authority reduce exposure but do not make an untrusted café, hotel, or
shared workplace LAN safe. Use Browser view only on a trusted private Wi-Fi
network, stop it when finished, or install the `purist` flavor. Self-signed TLS
is deferred because browser certificate warnings make the intended workflow
hostile and do not by themselves provide a trustworthy identity.

## Capture, transcription, and failure handling

`RECORD_AUDIO` is requested only when recording is first invoked. `CAMERA` is
requested only when photo capture is deliberately opened. CameraX returns a
JPEG buffer that is encrypted directly into the no-backup directory; Soma does
not create a MediaStore/gallery row or plaintext photo file.
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

Entry metadata is additive and cannot update `NoteEntry.text`, `createdAt`,
`updatedAt`, or `lastUserEditedAt`. Manual, LOCAL, and AI layers use separate primary
keys; replacing an AI result therefore cannot overwrite manual organization.
The metadata layer's source and derivation timestamp remain visible in Room,
while its tags, targets, and relation labels are encrypted. Before an automatic
result is written, the repository-facing coordinator rereads the entry and
rejects the result if its text changed or it was deleted during the request.
Disabling the toggle before completion also prevents persistence. A deliberate
edit or successful retranscription invalidates only the prior AI layer before
rederivation, preventing stale tags while retaining manual metadata. The deterministic
LOCAL layer is derived before optional cloud calls, checks the user's enabled
languages for code-switched dates, and is backfilled incrementally for pre-existing
notes only while Android reports that the battery is not low. On unchanged
text, provider/network failure leaves the prior AI layer untouched; a successful
empty result removes only that AI layer.

## Data loss and recovery

Uninstall removes the app database, audio, images, preferences, and Keystore keys.
Keystore invalidation can likewise make local ciphertext unrecoverable. Android
cloud backup and device transfer will not restore Soma. Export and verify a
portable encrypted backup before uninstalling or moving devices; there is no
account or server-side recovery path.

Deletion and “let go” are local state changes. Flash-storage wear levelling,
SQLite WAL pages, filesystem snapshots, and an already exported backup can
retain older encrypted bytes. Secure physical erasure is delegated to Android
device encryption and device-management controls.
