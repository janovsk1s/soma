# Changelog

Notable changes to Soma are documented here.

## 0.1.0-preview.14 — 2026-07-14

### Added

- Important recognizes phone numbers, deliberate 7–15 digit sequences, and
  labelled booking, confirmation, order, tracking, and reference codes locally
  in all eight supported languages.
- Long-press any current Important item to show it again tomorrow, in one week,
  or in one month. Due items return even if Soma was not opened on their exact day.

### Changed

- Grocery lists, ingredient lists, bullet lists, and spoken list headings stay
  grouped as one Important list instead of becoming unrelated suggestions.
- References and excerpts remain safely available in Important without being
  treated as unfinished work every morning. Explicitly scheduled ones still
  return through Today.
- Database schema 5, encrypted backup payload 6, and readable archive format 5
  preserve the show-again date; the human-readable CSV exposes it directly.

## 0.1.0-preview.13 — 2026-07-14

### Added

- Capture and manual Important drafts survive process recreation in a dedicated
  AES-256-GCM store protected by their own Android Keystore key. Draft text never
  enters Android's size-limited saved-instance-state Bundle.
- Light Phone Tool SDK readiness and remaining integration work are documented.

### Changed

- Day-flow text measurement is bounded per entry, and inactive Room observers
  stop after a short grace period to reduce idle work.
- Cloud settings disclose that provider requests may use cellular by default;
  the network policy now has cloud-flavor regression tests.

### Fixed

- Enabling `Wi-Fi only` now binds provider connections to Android's concrete
  Wi-Fi network instead of merely checking that Wi-Fi exists while an unbound
  request could still follow LightOS's cellular default route.
- Unfinished long-form drafts are persisted off the UI thread rather than copied
  into a Bundle that could overflow and crash during state saving.

## 0.1.0-preview.12 — 2026-07-13

### Changed

- Opt-in cloud transcription and AI Important suggestions now work over cellular
  as well as Wi-Fi by default. The Developer setting can still restrict provider
  requests to Wi-Fi for users who want to avoid mobile-data use.

### Fixed

- The experimental cloud flavor read provider responses without relying on
  `InputStream.readNBytes(int)`, which is unavailable below Android 13 and would
  throw `NoSuchMethodError` on `minSdk` 26–32 devices. Responses are now read with
  a bounded loop that keeps the same 2 MiB cap.

## 0.1.0-preview.11 — 2026-07-13

### Added

- The former Todo section is now Important and stores three explicit kinds:
  actions, lists, and source-linked excerpts. Existing todos migrate to actions.
- Entry options can open a read-only native text-selection screen. The selected
  word or phrase is copied into Important while the daily note remains unchanged.
- Rule-based detection recognizes explicit shopping-list headings in all eight
  supported languages and conservative multi-line bullet lists. Suggestions
  still require one deliberate tap and never add themselves.
- Database schema 4 and portable-backup payload 5 preserve Important kinds;
  readable CSV exports include the kind for long-term app-independent access.
- A documented performance and battery contract defines Light Phone capture,
  idle-work, model-lifetime, memory, and physical-device measurement gates.

### Changed

- The focused input editor now uses the full remaining screen height and scrolls
  internally instead of capping the writing area at 180 dp.
- Local Important detection runs after durable capture on a background dispatcher
  and bounds unusually large text, candidate counts, and detected list length.
- Important excerpts open their source on tap and omit the nonsensical “done”
  action; lists and actions remain directly completable.

## 0.1.0-preview.10 — 2026-07-13

### Added

- BYOK transcription vocabulary: one user-controlled list prompts local Whisper,
  Groq Whisper Large v3, and ElevenLabs Scribe v2. It is encrypted with its own
  Android Keystore key and included in both portable and readable exports.
- The vocabulary editor clearly discloses ElevenLabs' 20% keyterm surcharge;
  empty vocabulary sends no keyterms and keeps standard Scribe v2 billing.
- Hardware-aware local decoding keeps the Light Phone III on the existing
  conservative thread budget while capable Android devices use more threads and
  beam search for better tiny-model decoding.

### Changed

- The Todo screen now always uses the same bottom input line, spacing, type, and
  adjacent Paka-style `+` button as the daily note capture row.

## 0.1.0-preview.9 — 2026-07-13

### Fixed

- ElevenLabs Scribe v2 now receives each recording as one file so it retains
  language context across pauses and sentences. Soma no longer asks Scribe to
  detect the language independently for every short VAD fragment, which could
  make a later Latvian sentence drift into Russian.
- Selecting exactly one speech language still sends its explicit language hint
  (`lav` for Latvian); selecting several lets Scribe v2 handle code-switching
  across the complete recording. Local Whisper and Groq retain VAD chunking.

## 0.1.0-preview.8 — 2026-07-13

### Fixed

- ElevenLabs Scribe v2 results are no longer discarded when its detected
  language falls outside the user's selected language set; Soma keeps the
  transcript and uses the preferred supported language for rule processing.
- Wi-Fi-only cloud transcription now recognizes any connected Wi-Fi network
  with internet capability instead of checking only Android's default route,
  which can remain cellular on LightOS while Wi-Fi is connected.
- Safe ElevenLabs error categories survive local fallback: rejected key,
  missing speech-to-text permission, credits, rate limit, invalid request,
  network, and provider errors are now distinguishable without storing the
  provider response, transcript, audio, or API key.

### Added

- Voice-entry options include “transcribe again,” replacing the completed or
  failed job while preserving its encrypted audio and current text until the
  new result arrives.

## 0.1.0-preview.7 — 2026-07-13

### Changed

- The home capture row now uses Paka's exact `+` button instead of a microphone:
  tap the text line or `+` to write, and deliberately long-press the text line
  to begin a voice note. The `+` becomes a one-tap stop square while recording.
- Voice capture starts before the daily-note Room write. The encrypted partial
  file remains crash-recoverable, including the short interval before its entry
  row is inserted.
- Audio encryption keys and fixed format details warm while the home screen
  settles, and Android begins buffering speech before the encrypted container's
  disk sync, reducing the chance that the first words are clipped.

## 0.1.0-preview.6 — 2026-07-13

### Added

- Successful voice-entry details now identify the transcription engine that
  actually produced the text: local Whisper tiny, ElevenLabs Scribe v2, or
  Groq Whisper Large v3.
- When a selected cloud provider cannot run, the entry records that local
  Whisper completed the transcription and quietly explains whether Wi-Fi, an
  API key, or a provider failure caused the fallback.
- Transcription provenance is encrypted with the entry, included in portable
  backups, and written into both the Markdown and structured readable exports.

## 0.1.0-preview.5 — 2026-07-13

### Fixed

- New notes, entry edits, and todo edits now stay in the editor until encrypted
  storage confirms the write. A failed save keeps the text visible for retry.
- Continuous-note pages reserve their rendered top and bottom padding before
  packing entries, preventing the final entry from clipping.
- Flowing entries and todo suggestion actions retain 48 dp touch targets.
- Transcription language identification is constrained to the eight supported
  languages, picking the most probable among them per utterance. Short German
  speech can no longer surface as Russian or any other unsupported language.
- The exact Paka settings gear is back in the home header's top-left position.

### Changed

- The full-screen text editor is a faithful port of Paka's: it focuses itself
  when opened, keeps the save action visible above the keyboard, dims save
  while the text is empty, and saves trimmed text with the explicit save action.
- Entry timestamps sit on their own small line above each entry, so the text
  uses the full width of the screen.

- The home screen now shows the day as one continuous, information-dense note:
  entries flow with a faint creation time in a left gutter, transcribed voice
  entries carry a small speaker glyph, and pages pack as much text as fits,
  breaking at entry boundaries. Swipes still hard-cut whole screenfuls; the
  five-row rule continues to apply to lists elsewhere.
- Tapping the capture line opens the full-screen editor with a back arrow and
  save instead of focusing an inline field — the Light Phone has no navigation
  gestures, so focused inline fields could trap the user under the keyboard.
  System back also releases the todo quick-add line first.
- Text fields capitalize sentences (never passphrases).

### Added

- Long-pressing the day title opens a monochrome calendar of past days; days
  holding entries carry a small dot, future days stay inert.
- The separate cloud flavor again exposes opt-in Groq Whisper Large v3 and
  ElevenLabs Scribe v2 transcription plus Groq GPT-OSS todo extraction in the
  hidden Developer screen. ElevenLabs is the initial speech choice. Selected
  speech languages are sent when one language is chosen; multi-language chunks
  auto-detect and reject out-of-set results to the local Whisper fallback. Cloud
  and AI remain off by default.

## 0.1.0-preview.3 — 2026-07-13

### Fixed

- Voice recording works on the device: the Keystore audio-wrapping key now generates
  its own GCM nonce instead of being handed one, which Android prohibits
  (`CALLER_NONCE_PROHIBITED`) and which failed every recording and backup audio import.
- Speech is detected on the device: the voice-activity floor now sits below the Light
  Phone III microphone's measured low-gain speech level, which previously classified
  every recording as silence.
- Local transcription in preview/debug APKs now compiles whisper.cpp and ggml with
  native optimization while retaining debug symbols; unoptimized inference took
  several minutes for a short voice note on the Light Phone III.
- Whisper non-speech tokens such as `[BLANK_AUDIO]` are suppressed and cleaned before
  editable transcript text reaches the note.

### Changed

- The todos button in the home header is now a drawn open-ring glyph — the same marker
  the todo list uses for an open item — replacing the "todos" text label.
- The "still open" block is now driven by the tested `StillOpenPolicy` in `core`: when
  open todos exist, tapping it opens the todo list; entries marked "return later" are
  the target only when no todos are open.
- Opening a todo's source note entry now returns to the todo list, not to today's note,
  and edits or deletions started from there also come back to the todo list.

## 0.1.0-preview.2 — 2026-07-13

### Changed

- Dark mode is now the default across the app, launch screen, system bars, and browser
  view, matching Paka's native palette.
- Light mode is available only from the hidden Developer screen and persists across
  launches.
- The settings button uses Paka's exact gear geometry and pixel alignment.

### Added

- `browser` and `purist` APK flavors. The purist build omits both the browser server
  and the `INTERNET` permission.

## 0.1.0-preview.1 — 2026-07-12

### Added

- Initial preview: one auto-created daily note per day with text and voice entries,
  day paging, and five-row hard-cut lists matching Paka.
- On-device transcription with whisper.cpp (bundled multilingual tiny Q5_1 model),
  energy-based VAD, and per-utterance language detection.
- Rule-based todo suggestions in eight languages; suggestions become todos only after
  an explicit tap.
- Flat oldest-first todo list with done/archived page, 30-day quiet keep/let-go prompt,
  and a dismissible morning "still open" block.
- AES-256-GCM field encryption with Android Keystore keys, encrypted audio containers,
  and passphrase-encrypted portable backups with optional audio.
- Read-only LAN Browser view with one-time access code, session cookie, Wi-Fi-only
  binding, and 15-minute idle shutdown.
- Hidden Developer screen with demo mode, light mode, language override, and
  battery-saver transcription toggle.
