# Changelog

Notable changes to Soma are documented here.

## 0.1.0-preview.3 — 2026-07-13

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
