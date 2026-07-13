# Changelog

Notable changes to Soma are documented here.

## Unreleased

### Changed

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
- The settings gear returns to the top-left of the home header.

### Added

- Long-pressing the day title opens a monochrome calendar of past days; days
  holding entries carry a small dot, future days stay inert.

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
