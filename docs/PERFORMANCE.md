# Performance and battery contract

Soma treats performance as part of capture reliability. These budgets are
release gates to measure on Light Phone III hardware, not claims inferred from
an emulator or a development Mac.

## Capture path

- Cold launch to a tappable Today screen: target below 500 ms.
- Long-press to the first accepted microphone sample: target below 150 ms once
  Android permission has already been granted.
- Text save to the entry appearing in Today: target below 100 ms at p95.
- Text, audio, and photo persistence complete before rule detection, transcription, or
  any optional cloud work begins.
- Recording never waits for transcription and an audio failure never removes
  an already inserted entry.

`SomaViewModel` warms only the Android Keystore audio path during startup. It
does not open the microphone or load Whisper. Rule detection runs after a
successful save on `Dispatchers.Default`, is limited to 50,000 input characters
and 20 candidates, and emits at most one bounded list block.

## Idle and battery

- No always-listening microphone, continuous VAD, polling loop, analytics,
  crash reporter, or update checker.
- With the optional reminder and Browser view off, Soma schedules no repeating
  work. Transcription is a single unique drain that exits when its queue is
  empty.
- Local transcription remains disabled in Android battery saver unless the
  user explicitly overrides it in Developer settings.
- Light Phone uses the conservative local profile. Beam search and additional
  threads are selected only on devices with enough memory and CPU capacity.
- The Whisper model is loaded only inside transcription work and released when
  the drain closes. Realtime local transcription is not a Light Phone target.
- Browser view is explicit, visible, and stops on screen exit, app background,
  manual stop, or its idle timeout.

## Rendering and storage

- Home observes only the selected day and packs immutable entry blocks with
  stable ids. Fixed lists page five records at a time.
- CameraX is created only after a deliberate photo gesture and unbound when the
  capture screen leaves. Capture uses the latency-optimized mode at a bounded
  1280×960 target; the camera is never kept warm in the background. Visible
  encrypted photos are downsampled to at most 1080 px for display.
- The optional post-capture spoken comment reuses the encrypted audio recorder
  and existing one-at-a-time transcription queue. Soma does not keep the camera
  alive while the user records or types the comment.
- Room uses write-ahead logging; encryption, database access, export, audio,
  and transcription never run synchronously inside a Compose draw callback.
- Important detection runs only after Save or after a transcript lands, never
  for every typed character.
- Metadata writes are bounded, additive Room operations. They never block
  capture or rewrite the entry row; any future cloud derivation must begin only
  after the authored entry has been durably saved.
- The readable archive remains streaming ZIP output. Optional media is handled
  one recording at a time rather than cached permanently as plaintext.

## Verification before a stable release

Measure a release-signed build on a physical Light Phone III with Perfetto and
`adb shell dumpsys batterystats`, covering cold launch, ten text captures, first
record, a five-minute recording, local transcription, idle overnight, and a
15-minute Browser session. Record p50/p95 latency, peak RSS, CPU time, wakeups,
and battery delta. Fail the milestone if capture regresses even when background
features become more accurate.
