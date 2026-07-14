# Soma 2.0 direction — the local mind

Soma 1.0 ships first, from the current tree, under the 1.0 release contract.
This document fixes the direction of the cycle after it: Soma becomes
AI-native without ever becoming AI-dependent. It is an evolution of the
existing foundation — storage, crypto, voice, and the module boundaries stay.
There is no ground-up rewrite.

## Principles

- **AI is never load-bearing.** With every model off, missing, or deleted,
  nothing that works in 1.0 degrades. AI only adds to the additive
  metadata/proposal layer that already exists, and its output is always
  deletable and never rewrites authored text or timestamps.
- **Three privacy tiers, each opt-in:**
  1. *Purist* — no network code compiled in, no models. Unchanged, still
     enforced by the outbound-client guard and the permission contract test.
  2. *Local mind* — on-device models. Nothing ever leaves the phone; the cost
     is storage and battery only, so models download once on demand rather
     than inflating every APK.
  3. *BYOK cloud* — today's Developer toggles, off by default, for work the
     phone cannot do well.
- **Provenance everywhere.** Every derived tag, suggestion, recall hit, and
  reflection records which engine produced it, exactly as transcription
  already does.
- **Battery before magic.** On Light Phone III, indexing and model work run
  only while charging or above a battery floor, with the same single-drain
  discipline as transcription.

## Workstreams

1. **`:mind` — llama.cpp as `:whisper`'s sibling.** A small quantized
   instruction model behind the same pattern that already ships: SHA-256
   pinned, arm64, loaded only inside work, released when the drain closes.
   First use: local tags, todo candidates, and meal/workout parsing become the
   default engine, demoting cloud from "the smart path" to "the escape hatch".
2. **Recall — encrypted semantic memory.** A multilingual embedding model
   indexes entries into vectors stored encrypted like every other field.
   Semantic search without keywords, quiet "related from March" whispers on
   the day screen, and ask-your-bag answers that always link their source
   entries. At Soma's scale brute-force cosine over decrypted-in-memory
   vectors is sufficient; no vector database enters the build.
3. **Reflections — a mirror, not a feed.** One calm affordance on the day
   header: a locally generated three-sentence look at the week (meals,
   workouts, open Important items, recurring themes), provenance-labeled,
   kept only if deliberately saved.
4. **Tamper-evident history.** A hash chain over entry and log revisions,
   with hardware-backed signature checkpoints where StrongBox exists, so
   "original wording always recoverable" becomes verifiable rather than
   promised.
5. **`:calm-ui` — one design language, shared with Paka.** Extract the paged
   lists, settings rows, auto-fit text, and palette into a module both apps
   consume, then refresh screens one at a time in ordinary preview releases.
6. **Harness.** Screenshot tests across all eight locales, a full
   migration-chain test from schema 1 to current, and a scripted on-device
   smoke pass for the Light Phone III.
7. **Visibility.** Screenshots in the README, a small landing page, and
   F-Droid once 1.0 is stable. A calm product still has to be findable.

## Honest constraints

- Sub-1B local models are weak in Latvian, Estonian, and Lithuanian.
  Recall therefore leads with a genuinely multilingual embedding model —
  semantic search works in all eight languages even when local generation
  does not. Structured extraction stays schema-validated; fluent prose in
  small languages remains a BYOK concern until small models catch up.
- Model files are hundreds of megabytes. They are never bundled into the
  base APK, never required, and always deletable from Settings with their
  derived vectors.
- Every workstream ships incrementally through the existing preview channel;
  none blocks another, and none touches the storage, crypto, or backup
  formats without a versioned migration and fixture.

## Sequencing

1.0 first. Then, in order: `:mind` with local tags/todos, recall, reflections
— with `:calm-ui` extraction and screen refreshes threaded through each
preview, and tamper-evident history landing whenever the crypto design review
is done. "It works without AI" is re-verified at every step by the purist
flavor's existing gates.
