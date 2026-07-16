# Soma for iOS

Soma’s iOS client is a lightweight, fully native SwiftUI app. It keeps the
interaction model, capture flow, speech stack, and system integrations native to
iPhone while defining a portable context boundary for the Android client.

## Run it

Requirements:

- Xcode 26 or newer;
- iOS 18 or newer;
- an Apple development team for a physical iPhone.

Open `SomaIOS.xcodeproj`, select the `SomaIOS` scheme and an iPhone simulator or
connected iPhone, configure Signing & Capabilities if needed, then Run.

A physical Apple Intelligence-capable iPhone is recommended for testing
Foundation Models and downloaded speech assets. The simulator does not represent
every on-device model capability.

## Native iOS experience

The app uses SwiftUI, Observation, Swift concurrency, AVFoundation, Speech,
Foundation Models, App Intents, and App Shortcuts. It includes:

- native Today, Important, and Settings tabs;
- fast text, one-shot photo, and voice capture with protected local media;
- editable transcripts and audio playback;
- the same eight bundled forest backgrounds as Browser view, rendered natively
  with no WebView, network request, or duplicated image files;
- Siri and Shortcuts actions for adding thoughts and Important items;
- native sheets, context menus, file import/export, permissions, haptics, and
  symbol transitions;
- iOS 26 Liquid Glass controls and a minimizing tab bar, with native fallbacks
  on iOS 18–25.

The deployment target remains iOS 18. Newer APIs are isolated behind
availability checks.

## AI architecture

Soma is local-first.

Voice notes use Apple’s on-device speech stack by default:

- iOS 26: `SpeechAnalyzer` and `SpeechTranscriber`;
- iOS 18–25: `SFSpeechRecognizer` with on-device recognition required.

Optional cloud transcription supports Groq Whisper and ElevenLabs Scribe v2. If
a cloud request fails, Soma attempts Apple Speech and records which engine was
requested, which one was used, and a sanitized fallback reason.

Important suggestions use Apple Foundation Models on eligible iOS 26 devices.
Optional Groq extraction uses `openai/gpt-oss-20b`. Suggestions are bounded,
tied to the source note version, and never become Important items until the user
accepts them.

## Privacy and BYOK

Cloud features are independently controlled and off by default.

- API keys are supplied by the user and stored in the Keychain as
  device-only credentials.
- Keys are never written to the context store or exported.
- Cloud requests go directly from the device to the selected provider.
- Wi-Fi-only cloud processing can be enabled.
- Note text and recordings remain on-device unless the matching cloud feature
  is explicitly enabled.
- The local context snapshot, audio files, and captured photos use complete file
  protection. Photos stay in Soma's app container and are not written to Photos.
- Diagnostics retain sanitized categories, not provider bodies or credentials.
- Imported context is size-bounded and cannot reference local attachment paths.

## Context interchange

`SomaContextBundle` schema 2 is the portable boundary. iOS can export and merge
readable JSON bundles from Settings, while still accepting schema 1 bundles.
Records have stable identifiers, timestamps, tombstones, and transcription
provenance. Deliberate user edits are preserved over late machine transcripts.

Device-local filenames, audio bytes, AI suggestions, API keys, and settings are
not exported. The export UI warns that note text in the JSON is readable.

The Android app still needs an adapter between its richer Room model and this
schema. Its existing passphrase-encrypted `.soma` backup remains a separate,
restorable backup format; it should not be weakened or silently reinterpreted as
the cross-platform context format.

## Current limitations

- Cross-platform automatic sync is not implemented yet.
- Android import/export for `SomaContextBundle` is not wired yet.
- Audio and photo bytes are not included in context bundles. Imported voice
  entries keep their transcript but have no playable local recording; photo
  captions interchange as text without their device-local image.
- Captured photos are not automatically analyzed by the current AI pipeline.
- Camera preview and capture latency must be tested on a physical iPhone; the
  iOS simulator has no usable device camera.
- Apple Foundation Models require iOS 26, an eligible device, Apple Intelligence
  enabled, a ready model, and a supported locale.
- On-device speech requires permission and may need language assets to download.
