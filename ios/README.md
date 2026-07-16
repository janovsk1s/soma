# Soma for iOS

Soma’s iOS client is a fully native SwiftUI sibling of the Light Phone app. It
keeps the interaction model, capture flow, speech stack, and system
integrations native to iPhone while defining a portable context boundary for
the Android client.

## Run it

Requirements:

- Xcode 26 or newer;
- iOS 18 or newer (newest features light up on iOS 26);
- an Apple development team for a physical iPhone.

Open `SomaIOS.xcodeproj`, select the `SomaIOS` scheme and an iPhone simulator
or connected iPhone, configure Signing & Capabilities if needed, then Run.
HealthKit and Apple Intelligence require a physical, eligible iPhone.

## The capture bar

Capture never takes over the screen:

- typing expands the bar in place and keeps focus so several thoughts can be
  dropped in a row;
- press-and-hold the bar starts a voice note — release to keep it, slide right
  while holding to lock the recording open; an interruption (a phone call)
  finalizes the note instead of losing it;
- while recording, words appear live in the bar (on-device recognition, only
  once speech access has been granted, never a network);
- the camera is one tap; the date title returns to today on tap and opens the
  calendar on hold; the day rail refuses the future.

A dim one-line hint teaches the hold gesture once, then retires forever.

## Intelligence, in layers

Soma is local-first, and AI is never load-bearing:

1. **Deterministic rules first.** Obligation words for all eight Soma
   languages, the Latvian debitive `jā-` prefix, booking/order codes, and
   NSDataDetector-backed phone numbers and upcoming dates. No model, no key,
   no network.
2. **One on-device inference.** Apple Foundation Models (iOS 26, eligible
   devices) extract actions, meals, and workouts in a single call. Rules
   answer Important first; the model only fills what they left open.
3. **Optional cloud, one request.** Groq `openai/gpt-oss-20b` behind its own
   BYOK toggle, used only when the on-device model is unavailable.

Everything is accept-gated: Important suggestions, meal/workout proposals
(one quiet "Log?" chip per note), and photo text. Accepted logs render as dim
lines at the end of their day — no dashboards, no streaks.

Photos get one on-device Vision OCR pass at capture; recognized text is
offered as a "Keep photo text?" chip, and accepting makes the photo
searchable and feeds it through the same pipeline.

Voice notes are transcribed from the saved file: optional cloud transcription
(Groq Whisper, ElevenLabs Scribe v2) falls back to Apple Speech, with
per-entry provenance (requested engine, used engine, sanitized fallback
reason) and a diagnostics row.

Workouts arrive ambiently from Apple Health behind a Settings toggle:
read-only, one quiet line in their day, silence when there are none, stored
only if you keep it.

## Privacy and storage

- The context snapshot is encrypted at rest with AES-256-GCM; the key lives
  in the Keychain as device-only. Audio and photos use complete file
  protection and never touch the Photos library.
- API keys are Keychain device-only credentials; cloud requests go directly
  from the device to the selected provider, gated by independent toggles and
  an optional Wi-Fi-only switch.
- Deletion is soft: the trash restores or purges (media is freed at purge).
  Edits are append-only revisions — the original wording is always
  recoverable. Timestamps are immutable facts.
- Diagnostics retain sanitized categories, never provider bodies or
  credentials.

## Finding things again

Search (its own tab) matches notes and Important items with diacritic
folding — "zekes" finds "zeķes" — and jumps to the entry's day. A
Messages-style chevron returns to "now" after scrolling back through a day.

## Developer

Triple-tap the "Soma" row in About: light-mode switch (the app is dark-first,
like the Light Phone version), store counts, snapshot cipher, storage status,
and model availability.

A DEBUG launch argument `-soma-autorecord-seconds=N` drives one record-stop
cycle headlessly; with `xcrun simctl privacy <sim> grant microphone
com.soma.native` the whole recording loop is verifiable in the simulator
against the host microphone.

## Context interchange

`SomaContextBundle` schema 2 is the portable boundary. iOS exports and merges
readable JSON bundles from Settings (size-bounded, stable IDs, tombstones,
transcription provenance; deliberate user edits win over late machine
transcripts). The export UI warns that note text in the JSON is readable.

The Android app still needs an adapter between its richer Room model and this
schema. Its passphrase-encrypted `.soma` backup remains a separate,
restorable backup format; a cross-restore bridge for the documented formats
is the intended long-term integration surface and is not built yet.

## Current limitations

- Cross-platform automatic sync is not implemented; Android import/export for
  `SomaContextBundle` is not wired yet.
- Audio and photo bytes are not included in context bundles.
- Receipts, recipes, the LAN browser view, workbooks, and the eight-language
  localization of the Android app have no iOS counterpart yet.
- Apple Foundation Models require iOS 26, an eligible device, Apple
  Intelligence enabled, and a supported locale; speech recognition asks for
  permission once, on the first transcription.
