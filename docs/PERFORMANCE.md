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
- The downloadable base model was gate-measured on a Light Phone III
  (2026-07-15, cloudDebug, EFFICIENT base profile: 4 threads, greedy): a
  recording holding 105 s of continuous English speech decoded in 121 s of
  wall time (~1.2× audio duration; the kill criterion was ~4×), using
  roughly 3.5–5 cores at background priority. Peak process PSS during decode
  was 433 MB against a 70 MB idle baseline and returned to ~140 MB when the
  drain closed; battery temperature moved 31.0 → 32.0 °C. Base therefore
  ships with both acquisition paths; tiny remains the default.
- Browser view is explicit, visible, and stops on screen exit, app background,
  manual stop, or its idle timeout.
- LAN metadata insights and the static SVG graph are assembled only for an
  authenticated `/insights` or `/graph` request. They do no background indexing,
  JavaScript, or cloud work and render at most five connection rows/edges per page.
- Confirmed meal, recipe, workout, receipt, and archived log pages query at most six Room
  rows per request: five visible records plus one look-ahead row for paging. They
  do not decrypt the full archive or run nutrition analysis in the browser server.
- Optional cloud photo analysis uses a correctly oriented temporary JPEG bounded
  to 2,048 pixels and 4 MiB. The encrypted original is never recompressed or
  replaced; bounding only the disposable request reduces cellular transfer,
  provider latency, and peak memory.
- Browser-view forest backgrounds are eight bundled WebP files totaling under
  1 MiB. One is selected per session and served directly from memory; there is no
  remote image fetch, image decoding on the phone, or session-to-session cache file.
- Graph paging resolves only the visible source entries. Explicit entry-link
  targets are resolved to enforce tombstone filtering; ordinary tag and date
  edges do not cause per-entry database reads.
- The optional LAN Markdown export is generated only after an authenticated GET.
  HEAD is rejected without snapshot work, concurrent exports are refused, media
  is excluded, and the response buffer is wiped after the one download finishes.

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
  capture or rewrite the entry row. Deterministic LOCAL derivation runs before
  optional cloud work. An upgrade backfill pages old notes with a persisted cursor,
  runs only while the battery is not low, and can resume without duplicating data.
  Optional cloud derivation begins only after
  the authored entry or transcript has been durably saved, performs no polling
  or automatic retry loop, and is capped at 4,000 input characters, eight tags,
  and eight explicit date links.
- The readable archive remains streaming ZIP output. Optional media is handled
  one recording at a time rather than cached permanently as plaintext.

## Verification before a stable release

Measure a release-signed build on a physical Light Phone III with Perfetto and
`adb shell dumpsys batterystats`, covering cold launch, ten text captures, first
record, a five-minute recording, local transcription, idle overnight, and a
15-minute Browser session. Record p50/p95 latency, peak RSS, CPU time, wakeups,
and battery delta. Fail the milestone if capture regresses even when background
features become more accurate.
