# Soma 1.0 release contract

This is the minimum product and data contract for calling Soma 1.0. Preview
features may change, but a stable release must preserve these boundaries.

## Capture and return

- Text, voice, and photos save before transcription, detection, sync-like export,
  or any optional cloud work.
- Every entry keeps its creation timestamp. Deliberate edits retain the original
  wording and timestamp in an encrypted revision history.
- Important suggestions remain proposals until the user confirms them. Show-again
  dates, grocery lists, phone/number sequences, recipes, and explicit manual marks
  work without AI.
- Recording state is unmistakable in the bottom bar, recording starts within the
  physical-device performance budget, and attached photo commentary remains one
  linked entry rather than a second loose note.

## Food, recipes, and workouts

- Confirmed meal, recipe, and workout logs are encrypted, revisioned, linked to
  their original note/photo/voice entry, and independently exportable.
- European food data is local-first: Fineli and CIQUAL averages plus deliberate
  Open Food Facts lookups for packaged products. Every nutrition value is labelled
  package label, official average, estimate, or unquantified.
- A local exercise and machine catalogue supports fast manual workout entry.
  Optional cloud vision or text extraction creates an editable proposal only;
  spoken sets, repetitions, duration, and kilograms remain authoritative.

## Receipts and purchases

Receipt tracking is part of 1.0, not an unversioned experiment. It must include:

- an encrypted original receipt photo retained as evidence and linked to the daily
  note entry that captured it;
- an explicit **scan receipt** action. Soma must not silently treat every photo as
  a financial document;
- an editable proposal containing merchant, purchase time, currency, total, tax,
  discounts and deposits, plus line items with the raw printed description,
  corrected display name, quantity, unit price and line total;
- local arithmetic validation that compares line sums, discounts, tax and stated
  total. Mismatches stay visible and require confirmation rather than being hidden;
- immutable revisions for every accepted correction, with the original image and
  provider/raw extraction preserved separately from the user's corrected record;
- explicit separation between **purchased** and **consumed**. A grocery receipt may
  suggest pantry or spending records but must never create a meal automatically;
- local merchant-description mappings so repeated corrections improve future
  receipts without sending a personal purchase history to a provider;
- a read-only Browser-view Purchases section with five records per page, merchant,
  date, total, item lines, source note and revision history; and
- portable encrypted backup plus readable JSON/CSV/Markdown export. Payment-card,
  loyalty-account and other unnecessary identifiers must not be extracted or kept.

On-device OCR is the free privacy-preserving default. Optional BYOK cloud vision
may improve difficult receipts, but it must be off by default, clearly disclose
off-device processing, use the existing cellular/Wi-Fi policy, and save only a
proposal. A provider failure can never remove the receipt photo.

## Browser view

- The temporary LAN server stays explicit, read-only, session-authenticated and
  bound to a concrete private Wi-Fi address. It prefers port `8787` with safe
  fallback, while explaining that DHCP may still change the phone IP.
- Notes, Important items, confirmed logs, Insights, graph, authenticated media and
  deliberately enabled export share one calm, monochrome, responsive design.
- One of eight bundled localized-country forests is fixed for each session. No
  image host, client-side framework, telemetry, plaintext disk cache or outbound
  request is allowed in the browser-only flavor.

## Automation and cloud AI

- Soma remains fully useful offline. Local rules and catalogues run first.
- Cloud transcription, metadata, receipt extraction, food/workout proposals and
  future automation are separate off-by-default Developer options using BYOK.
- Background work is durable, bounded, single-flight where required, battery-aware,
  and observable. Capture commits first; retries cannot duplicate accepted logs.
- Every result records engine/provider provenance and failure state. User-authored
  text and media are never overwritten by a model response.

## Portability and release gates

- Portable encrypted backup covers notes, every text revision, Important items,
  metadata, audio, photos, food/recipe/workout logs, receipts/purchases, their
  revisions, settings needed to interpret the data, and format/version metadata.
- A readable export uses documented, ordinary formats and remains useful if Soma
  disappears. The repository documents each format and maintains restore fixtures.
- All eight localizations cover every user-facing production flow.
- `test`, `lint`, debug/release/preview assembly, permission checks, no-outbound-
  client checks, migrations, backup round-trips, and physical Light Phone III
  performance/battery measurements pass before the 1.0 tag.
- Stable signing keys and a reproducible release process replace the preview debug
  identity before users are asked to trust 1.0 with long-lived data.
