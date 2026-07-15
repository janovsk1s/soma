# Changelog

Notable changes to Soma are documented here.

## Unreleased

### Added

- Workbooks in the Browser view. A new /workbook page imports a guided
  journaling programme — a friend's 25-day workbook, or anything in the tiny
  documented text format — by pasting it as plain text. The page shows the
  next unanswered day's prompt with a factual "3 · 25" position, and the
  answer becomes an ordinary entry for today, linked back to its prompt.
  There are no streaks and no missed days: a skipped prompt simply stays
  next, which is also what the format's paper ancestors intend. The workbook
  text is encrypted at rest and never enters backups; answers and their links
  are ordinary user data and always were.

- A downloadable, deletable local Whisper *base* model (~57 MB, roughly half
  tiny's error rate, the biggest gain on exactly the languages that need it) —
  better transcription with no cloud key and no privacy trade. Settings →
  local model chooses between tiny (built in) and base. The cloud flavor can
  fetch base in-app over a Wi-Fi-bound, resumable download; every flavor can
  import the model file through the system file picker, so the purist and
  browser builds' no-outbound-HTTP guarantee is untouched. Either way the file
  becomes loadable only after matching a SHA-256 pinned in source. Tiny stays
  bundled and takes over automatically whenever base is absent, and transcript
  provenance records which engine actually ran (backup payload v12, with a new
  committed restore fixture).
- Search. A quiet "search" row under the calendar opens a screen that finds a
  word or phrase across entries, Important items, and logs — case- and
  diacritic-insensitive ("janis" finds "Jānis"), newest first, entirely
  on-device: nothing is indexed and no derived plaintext lands on disk. The
  Browser view gains the same search as a localized /search page whose hits
  link straight to the day, item, or log that holds them.
- Recordings in the Browser view now draw a small monochrome waveform above
  the audio control, decoded on demand and never persisted.
- The home screen shows one quiet, dim line naming the next undiscovered
  gesture (hold the line to speak, hold + for a photo, tap the date to look
  back). Each hint retires forever the first time its gesture is used; there
  are no popups and nothing repeats.
- Deleting forever from the trash now takes a second, clearly-worded tap on
  the same row, since it is the one action Soma cannot undo.
- docs/INTERACTIONS.md records the tap/long-press contract every screen
  follows.

### Fixed

- Receipt scans are far kinder to free provider tiers. The photo sent for a
  receipt proposal now uses a tighter pixel budget (vision models charge
  tokens per pixel, so this roughly triples how many scans fit in a
  per-minute budget), and when the provider still answers "rate limited"
  with a short wait, the scan waits exactly that long and retries once with
  a smaller render instead of silently giving up.
- Receipts with printed deductions now parse honestly. Discounts and deposit
  refunds ("NIMM MEHR −0,50", Pfand returns, the trailing-minus style some
  registers print) used to lose their sign and be added as positive charges;
  they are now signed lines, the arithmetic reconciles, and the editor accepts
  a minus. German-language totals ("Summe", "Gesamtbetrag", "zu zahlen") are
  recognized instead of being misread as purchases. Backup payload v13 with a
  committed restore fixture carries the signed amounts.
- Photo meal, workout, and receipt proposals can now walk an ordered list of
  vision models, so a retired model degrades to the next candidate instead of
  killing the feature. Groq currently offers only one live vision model (its
  other one retires 2026-07-17 and names this one as its replacement), so the
  shipped list is one deep today; the mechanism makes a future successor a
  one-line addition. Account-level failures (key, credits, rate limits) still
  surface immediately and are never retried against another model.
- Cloud vision uploads are bounded to just under 3 MB so every candidate
  vision model accepts them; photos were already downscaled for cloud analysis
  and the encrypted original keeps its full quality.
- The no-internet transcription path (cloud transcription on, network dead) is
  now pinned by a unit test: it falls back to the local Whisper model and
  records the network error in provenance and diagnostics.

## 0.1.0 — 2026-07-15

First stable release of the 0.1.0 line, promoting preview.35.

### Added

- Browser view can now edit: add a text entry to a day and edit an entry's
  text from the keyboard of any device on the Wi-Fi. Writes go through the same
  encrypted, revision-preserving path as the app (new entries are timestamped,
  edits keep the prior wording as a revision, nothing is deleted from the web),
  and every write carries a per-session CSRF token. This trades the previous
  read-only guarantee for editing convenience — enable Browser view only on
  trusted Wi-Fi; see docs/THREAT_MODEL.md. Adding/editing Important items and
  logs from the web is planned as a follow-up.

### Changed

- Browser view redesigned: a masthead with the current section marked, a nav
  that fits instead of clipping on phones, warm off-white ink on near-black,
  the localized forest present through a frosted reading panel, an editorial
  type scale with uppercase tracked labels, tabular figures for dates and
  totals, a monochrome audio player, and row and history detailing.
- Every Browser-view heading, label, empty state, stat, and pager control is
  now localized in all eight languages, matching the already-localized
  navigation (previously many were hardcoded English).

### Fixed

- The camera's back-arrow top bar no longer disappears once the live preview
  starts: the preview is clipped to its area and the bar is drawn as an opaque
  overlay above it.
- The day title opens the calendar on a plain tap, not only a long-press, so
  the date is a discoverable way in.
- The calendar now rings the day you are currently viewing (distinct from
  today's bold weight), so opening it shows where you are instead of only
  highlighting today, and the next-month arrow is dimmed-but-visible at the
  current month rather than invisible.

- Latvian debitive obligations (jānopērk, jāpiezvana, jāaizved…) are now
  detected locally, so a note like "Jānopērk olas, piens, zeķes" becomes one
  complete Important suggestion instead of falling through to the cloud, which
  had been silently dropping items from such lists. The AI todo prompt also now
  forbids shortening an enumeration.
- Every settings and options label now renders at one fixed size across every
  row, page, and screen, replacing the previous auto-fitting that made sizes
  vary within and between screens. Labels too long for one line wrap to a
  second line instead of shrinking or ellipsizing.
- The still-open banner no longer shows empty "· 0" segments; it lists only the
  non-zero counts.
- The Browser-view export row right-aligns its on/off value like every other
  settings row, and Important/Logs metadata lines align on one baseline.
- Important and Logs rows with two-line text no longer clip their date and
  metadata line inside the fixed five-slot page; wrapped titles use a tighter
  line height so both lines and the metadata always fit.
- The new cloud-error diagnostics row uses a compact label in all eight
  languages so its value (reason and time) never crowds the label into
  ellipsis.

### Added

- A quiet "last cloud error" row in Developer → cloud experiments shows the
  most recent cloud failure category and time (key missing or rejected, needs
  credits, rate limited, Wi-Fi required, network, provider), in all eight
  languages. Ambient AI failures were previously invisible, which made an
  inert BYOK setup impossible to diagnose. Only the safe category and time
  are stored — never provider responses, note text, or key material. Tapping
  the row clears it.

## 0.1.0-preview.34 — 2026-07-14

### Fixed

- Browser view now works on IPv6-only Wi-Fi networks: bind-address selection,
  configuration validation, and the peer check accept IPv6 unique-local
  addresses (fc00::/7) alongside IPv4 site-local ranges.
- Keystore text decryption now rejects invalid UTF-8 the same way the portable
  cipher does, instead of silently substituting replacement characters.
- `verifyNoHttpClients` failed on AGP artifact-variant ambiguity whenever it
  actually ran (it is part of `check`), so the guard was unenforceable. It now
  inspects resolved dependency graph coordinates and passes.

- The eight cloud-transcription fallback and retry strings now exist in all
  eight languages; they previously fell back to English in every non-English
  locale. `app_name` is marked non-translatable as a brand name.

### Added

- GitHub Actions CI runs every module's unit tests (all three flavors), the
  outbound HTTP-client guard, release lint for every flavor, and
  debug/preview/release assembly on pushes and pull requests.
- Release-gate tests: localization completeness with placeholder checking
  across all eight languages, an exact per-flavor manifest permission
  contract, and a committed portable-backup restore fixture
  (`portable-backup-v11.somabackup`) that pins the on-disk format across
  releases.

## 0.1.0-preview.33 — 2026-07-14

### Added

- `Register → receipt` now opens a dedicated review screen with the unchanged
  source photo, merchant, currency, printed totals, removable purchased-item
  rows, quantities, exact line totals, and optional categories.
- Calm reconciliation shows priced-item sums and any difference from the
  printed total. Differences never overwrite the receipt or block confirmation.

### Changed

- Photo-only receipts can be registered without inventing a merchant. Receipt
  details and later edits use the same structured form in all eight languages.
- EU amount parsing now respects declared three-letter currencies, comma or dot
  decimals, grouped thousands, and repeated identical purchase lines.
- Groq receives a correctly rotated, bounded analysis copy of a receipt photo,
  reducing transfer time and memory while the encrypted original remains intact.
- App and Browser receipt details no longer expose Soma's internal editable
  interchange lines as duplicated body text.

### Verification

- Added coverage for European currencies and number formats, duplicate receipt
  lines, photo-only records, partial-price reconciliation, and Browser rendering.

## 0.1.0-preview.32 — 2026-07-14

### Changed

- Entry details now has one clear, localized `register` action. It opens the
  meal, recipe, workout, or receipt chooser while preserving the source entry.
- A blank photo is no longer assumed to be a receipt. Inline receipt proposals
  require a receipt signal in the entry text or transcript; any photo can still
  be registered deliberately from its details menu.

## 0.1.0-preview.31 — 2026-07-14

### Added

- Today now offers subtle, local `→ meal?`, `→ recipe?`, `→ workout?`, and
  `→ receipt?` actions after high-confidence text or transcription signals in
  all eight supported languages. A new, uncommented photo offers the receipt
  action immediately; nothing is logged until the user confirms it.
- Receipt logs preserve the source photo/note and store encrypted, revisioned
  merchant, currency, exact total/tax, purchased-item, quantity, price, and
  optional category data. Manual entry works offline; optional Groq vision fills
  an editable proposal and never replaces the original evidence.
- The Browser view has an authenticated, read-only Receipts page with five
  records per page, purchased items, exact totals, source links, and localized
  navigation.

### Changed

- Tapping an inline tracking proposal bypasses the long-press category chooser.
  When the separate Groq tracking toggle is enabled, enrichment begins
  automatically but cannot overwrite text after the user starts editing.
- Portable backup payload 11, readable archive format 10, Markdown vault format
  5, CSV, and JSON now carry receipt purchase data for use without Soma.

### Verification

- Added multilingual false-positive coverage, exact receipt parsing, encrypted
  payload compatibility, export, and escaped Browser-view receipt tests.

## 0.1.0-preview.30 — 2026-07-14

### Added

- Browser view now includes authenticated, read-only meal, recipe, workout, and
  archived-log pages. Records keep their source-note link, nutrition provenance,
  workout sets, revision count, and five-per-page navigation.
- Eight bundled monochrome forest landscapes represent Soma's current localized
  countries. One background is selected for each Browser-view session and never
  requires a remote image request.
- Receipt and purchase tracking is now an explicit Soma 1.0 requirement, with
  preserved receipt evidence, editable structured proposals, arithmetic checks,
  revision history, export, and a read-only purchases page.

### Changed

- Browser view has a calmer, responsive monochrome reading surface with restrained
  typography, flat separators, localized primary/log navigation, and no dashboard
  decoration or JavaScript framework.
- Access-code copy, food quantities, nutrition provenance, exercise repetitions,
  and durations follow the selected Soma language instead of leaking English into
  Latvian or the other localized Browser-view surfaces.
- Soma prefers LAN port `8787` on every session so an address can usually be
  bookmarked. It falls back automatically if that port is already occupied and
  clearly notes that a router can still change the phone's IP address.

### Security and performance

- The forest is the only pre-authentication asset and is application-bundled with
  no user data. All logs remain behind the one-time code and read-only session.
- Log pages query only the five visible encrypted records plus one look-ahead row;
  the server does not decrypt the full tracking archive.

## 0.1.0-preview.29 — 2026-07-14

### Added

- Deterministic on-device metadata turns authored hashtags and explicit dates in
  enabled languages into a separate encrypted LOCAL layer without changing note
  text. Existing notes are enriched incrementally while the battery is not low.
- Accepted Important suggestions can quietly prefill a clearable show-again date
  from explicit dates, weekdays, or “tomorrow” in the user's enabled languages.
- Browser view can optionally create a text-only Markdown vault for use with the
  user's own tools. Export authority is off by default and lasts for one running
  Browser-view session only.

### Security

- The export confirmation names every included category, including food/workout
  logs and all earlier wordings. It is localized in all eight Soma languages and
  clearly identifies the archive as plaintext over local HTTP.
- Export remains behind the one-time-code session, accepts only an explicit GET,
  permits one export at a time, excludes audio/photos, and wipes the completed
  archive buffer after the response closes. HEAD cannot trigger archive work.

### Fixed

- Local metadata runs before optional cloud Important or metadata calls, so a
  slow network cannot prevent offline enrichment.
- Insights now reports LOCAL layers separately, and code-switched notes are
  checked against the selected speech-language set.

## 0.1.0-preview.28 — 2026-07-14

### Fixed

- Transcription vocabulary is now a visible five-per-page list. Tap a saved term
  to edit it, or deliberately long-press it to open the remove action; additions
  and removals persist immediately and the current count is always visible.
- Text fields now tell Android's keyboard the active Soma language. Latvian meal,
  recipe, workout, and vocabulary entry therefore keep Latvian input behavior
  instead of allowing the IME to fall back to English.
- Soma now declares all eight supported application locales to Android and has a
  regression test for the Latvian meal-editor and vocabulary resources.

### Changed

- Manual workout fields follow one consistent Exercise → Sets → Repetitions →
  Kilograms focus sequence. The keyboard's Next and Done actions work, the form
  scrolls above the IME, and Done submits a valid entry just like the save row.
- Every supported language now includes vocabulary-list management, validation,
  removal, and provider-cost copy.

## 0.1.0-preview.27 — 2026-07-14

### Added

- Encrypted, revisioned meal, recipe, and workout logs linked back to the original
  note, voice entry, or photo without rewriting that source material.
- A quick workout form and local exercise/machine catalogue for recording sets,
  reps, and kilograms without AI or a network connection.
- 7,722 bundled European food records from Fineli and CIQUAL, with explicit
  provenance and local quantity calculations, plus deliberate Open Food Facts
  barcode lookup in the cloud flavor.
- Optional, off-by-default Groq text and photo proposals. Suggestions remain
  editable drafts until the user confirms them; models never invent nutrition.
- Durable log data and revision history in encrypted backups, readable archives,
  and Markdown-vault exports.

### Changed

- Groq Whisper Turbo is the fresh-install cloud transcription default, with Large
  V3 available as an accuracy option and ElevenLabs Scribe v2 retained for users
  who prefer its Baltic-language results. All cloud providers remain BYOK.
- Cloud processing works on cellular unless the user deliberately enables the
  Wi-Fi-only setting; offline capture and local tracking never depend on it.
- The settings control uses Paka's exact Light gear asset by default. Its drawn
  fallback and light theme remain available only from Developer settings.
- Tracking, provenance, nutrition, workout, and cloud-privacy copy is available
  in every language Soma currently offers.

### Data

- The Room schema advances to version 9 with a tested migration for tracking logs
  and immutable revision snapshots.
- Fineli is attributed under CC BY 4.0 and CIQUAL under the French Open Licence
  2.0; the generated catalogue digest is recorded in third-party notices.

## 0.1.0-preview.26 — 2026-07-14

### Changed

- A photo with no comment now has a persistent "record about photo" action in
  its long-press options. The recording, original audio, and later transcript
  attach to the existing photo entry and keep the photo's single creation
  timestamp instead of creating a second voice entry.

## 0.1.0-preview.25 — 2026-07-14

### Fixed

- Audio playback now stops automatically when leaving the entry reader/options
  flow, so returning to Today or another screen can never leave an invisible
  recording playing without a stop control.

## 0.1.0-preview.24 — 2026-07-14

### Changed

- Photo capture now has a labeled shutter, a retryable capture-failure state, and
  a clear post-photo choice: tap the bottom bar to write or hold it to record.
  Neither the keyboard nor microphone starts unexpectedly after taking a photo.
- The entry detail view renders photos at their real rotated aspect ratio on the
  app background, removing the fixed gray bands above and below portrait photos.
  Detail text now uses the same compact 18/23 typography as the Today note.
- Important items open into a full read view when tapped; their trailing mark is
  the deliberate completion control and a long press still opens item options.
- Deleted-item taps now open restore/delete options, the Today undo affordance
  expires after five seconds, and Browser/Cloud/About screens have less duplicate
  copy and clearer privacy wording.

### Safety

- Permanent deletion remains confined to Deleted Items, including development
  APKs, so an ordinary entry-options tap cannot bypass the recoverable trash.

## 0.1.0-preview.23 — 2026-07-14

### Added

- The authenticated LAN Browser view has a separate, read-only connection graph.
  It draws up to five metadata edges per page as a server-rendered monochrome SVG,
  labels every edge as manual or AI-derived, and links visible entry nodes back to
  their daily notes.

### Security and performance

- The graph has no mutation endpoint, JavaScript, graph dependency, cloud summary,
  disk cache, or phone-side rendering. Existing session authentication, no-store
  responses, and tombstone filtering apply unchanged.
- Normal graph pages load at most five visible source entries. Explicit entry-link
  targets are resolved only to omit deleted targets; tag/date edges need no extra
  entry reads. Display labels are bounded without altering stored or exported data.

## 0.1.0-preview.22 — 2026-07-14

### Added

- The authenticated LAN Browser view now has a restrained, read-only Insights
  page. It shows local counts for annotated entries, manual and AI layers,
  tags, and links, followed by topic/date/entry connections five per page.
- The page uses the same dependency-free HTML and monochrome navigation as the
  existing Days and Important pages. It adds no JavaScript graph library and
  does not ask a cloud model to summarize the user's notes.

### Security

- Metadata is decrypted only for an authenticated request and remains covered
  by the existing no-store response, short-lived session, LAN-only binding, and
  read-only route policy. Nothing is cached as plaintext on disk.
- The storage query excludes metadata owned by tombstoned entries. Entry links
  whose target was deleted are also omitted from the page and its link total.

### Performance

- Insights are calculated only when that browser page is opened. There is no
  background indexing, polling, phone-side rendering, or idle battery work;
  rendered connection rows remain bounded to five per request.

## 0.1.0-preview.21 — 2026-07-14

### Added

- The experimental cloud build has a separate, off-by-default Developer toggle
  for automatic entry metadata. With the user's Groq key, a newly saved or
  edited entry can receive up to eight normalized topic tags and explicit date
  links through `openai/gpt-oss-20b`.
- Automatic metadata also runs after a transcript completes. Tags and links use
  the encrypted AI layer added in preview.20 and therefore appear in readable
  JSON and Markdown-vault exports without changing the daily note.

### Security

- Browser and purist builds compile only a no-op implementation. The HTTPS path
  remains in the cloud source set, uses the existing BYOK/cellular-or-Wi-Fi
  policy, sends only the new or edited entry, and is disabled by default.
- Provider output is bounded and validated locally. Relative or invalid dates
  are discarded, a delayed response is rejected if the entry changed meanwhile,
  and disabling the toggle before completion prevents the result from saving.
- A deliberate edit or successful retranscription invalidates only metadata
  derived from the prior wording. Manual organization is preserved, and a
  provider failure cannot leave stale AI tags attached to changed text.

### Performance

- Capture and transcription completion are committed before optional metadata
  work starts. There is no polling or retry loop, and the original entry row and
  user timestamps are never rewritten.

## 0.1.0-preview.20 — 2026-07-14

### Added

- A new encrypted, additive metadata layer can retain normalized tags and links
  to entries, dates, or other tags without changing the authored note. Manual
  and AI layers are independent, so later automation cannot overwrite a user's
  own organization.
- Portable encrypted backups, readable archives, and Markdown vaults now carry
  metadata. The readable ZIP includes structured `data/metadata.json`; vault
  exports use ordinary tags and wikilinks that remain useful without Soma.

### Changed

- Database schema 8, encrypted backup payload 9, readable archive format 8,
  and Markdown-vault format 3 add versioned entry metadata. Existing preview
  databases and backups migrate forward without rewriting entries or their
  creation/edit timestamps.

### Security

- Metadata tags, link targets, and link relations are encrypted at rest with
  AES-256-GCM and authenticated against the owning entry and metadata source.
  Only the layer's source and derivation time remain queryable in Room.

## 0.1.0-preview.19 — 2026-07-14

### Added

- After a photo is saved, the bottom input quietly changes to "add a note about
  the photo…". Tap it to type a caption or hold it to record an encrypted spoken
  comment on that same photo; starting a different entry dismisses the prompt.
- Spoken photo comments use the existing local, ElevenLabs Scribe v2, or Groq
  transcription path and expose the same playback, provenance, retry, export,
  backup, and authenticated LAN controls as ordinary voice notes.

### Changed

- Image entries can carry both an encrypted JPEG and encrypted WAV without
  changing the photo's original creation time, position, or edit history.
- Deleting or purging only one attachment keeps the other usable. Interrupted
  photo-comment recordings are reconciled on the next launch instead of being
  discarded as orphaned audio.

## 0.1.0-preview.18 — 2026-07-14

### Added

- Long-pressing the Paka-style `+` opens a minimal CameraX screen; one shutter
  press saves a photo entry and returns to the continuous daily note.
- Photos render inline in the paged day flow and on the full entry screen. They
  keep the same subtle creation timestamp, immutable position, optional caption,
  return-later action, and recoverable deletion behavior as other entries.
- Portable encrypted backups rewrap JPEG originals with a fresh per-device
  image key on import. Readable and Markdown-vault ZIPs export ordinary JPEGs,
  while the authenticated LAN view decrypts only the requested image in memory.

### Changed

- Database schema 7, encrypted backup payload 8, readable archive format 7,
  and Markdown-vault format 2 add image attachment metadata and media payloads.
- The backup toggle now describes all optional media rather than audio alone.

### Security

- Camera permission is requested only on first use. Camera JPEG bytes are
  encrypted before any disk write, using a dedicated Keystore wrapping key and
  a fresh AES-256-GCM data key per photo. Soma never writes to the gallery or a
  plaintext cache.

## 0.1.0-preview.17 — 2026-07-14

### Added

- Backup now offers a separate one-way Markdown vault ZIP for Obsidian, Logseq,
  or any text editor. Daily notes use root-level `YYYY-MM-DD.md` files with
  portable frontmatter and local-time entry headings.
- `Important.md` preserves open, done, and let-go items as Markdown checklists.
  Source-linked items point back to the exact daily-note entry with stable,
  path-safe Obsidian block links; show-again dates remain visible.
- Edited entries link to dedicated `history/` files containing the original,
  every intermediate wording, replacement timestamps, and the current wording.
- Optional recordings are standard WAV files in `media/` and are embedded from
  their daily entries. The vault also includes a small versioned manifest.

### Changed

- Plaintext exporters now share one tombstone filter, so deleted entries,
  deleted audio, orphaned source links, and their histories are consistently
  omitted while encrypted portable backups remain recoverable.
- The Markdown ZIP uses deterministic entry ordering and timestamps. Imported
  or legacy entry ids are hashed before becoming file paths or block anchors,
  preventing path traversal and collisions with user-facing names.

### Security

- The Markdown vault is explicitly plaintext and one-way: Soma never imports
  it, never includes API keys, and tells the user to store it somewhere trusted.

## 0.1.0-preview.16 — 2026-07-14

### Added

- Edited entries now expose a five-per-page edit history on the phone. The
  original wording, every intermediate wording, and the current wording remain
  readable with the time each became current.
- A previous wording can be restored without overwriting history: Soma records
  the current wording as another encrypted revision first.
- The LAN browser view exposes escaped previous wordings under each edited
  entry without JavaScript or plaintext disk caching.
- Settings now includes a persistent Deleted items screen. Tap restores an
  entry or its audio; a deliberate long-press opens the permanent purge action.

### Changed

- Deleting an entry or recording now writes a tombstone instead of destroying
  the Room row, revision history, or encrypted audio file. A one-tap Undo stays
  visible on Home, and recovery remains available after an app restart through
  Deleted items.
- Readable exports and the LAN view omit tombstoned entries and audio. Encrypted
  portable backups retain tombstones and recoverable encrypted media.
- Soft delete, undo, and audio-only removal preserve `createdAt`, `updatedAt`,
  `lastUserEditedAt`, position, and revision history exactly.
- Database schema 6, encrypted backup payload 7, and readable archive format 6
  add versioned tombstones with backward-compatible reads.

### Fixed

- New entries now allocate positions after hidden tombstones, preventing a
  deleted final entry from blocking the next capture.
- Transcription workers skip deleted entries and deleted audio without waking a
  cloud or local engine.

## 0.1.0-preview.15 — 2026-07-14

### Changed

- The bottom capture bar now changes immediately from its writing hint to
  `starting recording…`, then shows a calm elapsed recording timer and a square
  stop action. While the encrypted container is finalized it reads
  `saving recording…` and ignores repeated taps.
- Recording elapsed time uses Android's monotonic clock, so timezone or manual
  wall-clock changes cannot make the timer jump.

### Fixed

- Day-first, month-first, dotted, slashed, and year-first calendar dates no
  longer become false phone/reference suggestions. Regression coverage runs the
  common formats through all eight supported languages.

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
