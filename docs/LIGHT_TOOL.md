# Soma as a Light Phone III tool

The Light Phone announced a developer program for the Light Phone III in 2026: a
LightOS SDK plus a curated **Tool Library** for distributing third-party "tools."
This note records how Soma maps onto that program and what remains before Soma
could be submitted. It is groundwork, not a commitment — the SDK and its rules are
still evolving through 2026.

## What the program is

- A **tool is a native Android APK** built the way Soma already is: Kotlin, Jetpack
  Compose, Coroutines, MVVM. The SDK (`lightphone/light-sdk`) provides a `:sdk:client`
  library with `LightScreen` / `LightScreenViewModel` base classes and a `navigateTo()`
  navigation model, an open-source UI/UX library, and an emulator.
- Tools get **permission-gated** access to push notifications and on-device media
  (photos, video, audio, files). The SDK "gently but broadly" restricts which Android
  APIs and third-party libraries are allowed, to keep a secure, distinctly *light*
  experience; stable open-source libraries can be requested for allowlisting.
- **Distribution tiers**: Light-approved tools (Light-signed + allowlisted), SDK-built
  tools (Light-signed, no manual review), and any tool via ADB sideload. As of
  mid-2026 the seamless-distribution infrastructure is still in progress, so **ADB
  sideload is the current path**; the user-facing Tool Library is targeted for later
  in 2026.
- **Requirements**: open-source, non-commercial (no ads, subscriptions, or in-app
  purchases), a clear intentional purpose, strong privacy, and the white-on-black
  LightOS look.

## How Soma already fits

Soma is unusually well-aligned with the Tool Library ethos:

| Requirement | Soma today |
| --- | --- |
| Native Kotlin/Compose/MVVM APK | Yes — `app` module is Compose + `SomaViewModel` (MVVM). |
| Open source | Yes — GPL-3.0-only. |
| Non-commercial, no ads/IAP/analytics | Yes — no accounts, ads, analytics, crash reporting, or engagement mechanics. |
| Privacy | Yes — AES-256-GCM at rest, Keystore keys, `check_no_outbound_clients.sh`, documented threat model. |
| White-on-black look | Yes — Paka's native dark palette (`SomaPalette`). |
| Clear intentional purpose | Yes — calm daily notes and mind offloading. |

## Recommended submission candidate: the `purist` flavor

- **`purist`** (no `INTERNET` permission, no LAN module) is the cleanest Tool Library
  submission: fully offline, no network surface at all.
- **`browser`** (inbound-only LAN, no outbound client) is acceptable but adds an
  `INTERNET` permission and a server surface that Light may not want in a curated tool.
- **`cloud`** should stay a **separate sideload**, not a Tool Library submission: its
  BYOK cloud transcription talks to paid third-party providers, which does not fit a
  curated, non-commercial platform even though the user supplies their own key.

## Gaps to close before submission

1. **API/library allowlist audit.** Check Soma's runtime surface against the SDK's
   permitted-API list and request allowlisting where needed. Notable items to verify:
   the native NDK whisper.cpp/ggml transcription, the `WorkManager` transcription drain,
   Room, `kotlinx-coroutines`, `AudioRecord`, and Android Keystore.
2. **Adopt `:sdk:client`.** Port Soma's screen layer to `LightScreen` /
   `LightScreenViewModel` and the SDK's `navigateTo()`. Soma's hand-rolled `AppRoute`
   `when`-based navigation (`MainActivity.kt`) and per-screen `BackHandler`s map cleanly
   onto that model, so this is adaptation, not a rewrite.
3. **Signing and distribution.** Resolve Light-signing for the SDK-built or Light-approved
   tiers; until then, distribute via ADB sideload (as the previews already do).

## Integration plan (deferred)

Actually wiring the SDK is intentionally left for a session that has the SDK dependency
and a working Android toolchain, so the result compiles and can run in the emulator:

1. Add `lightphone/light-sdk` to the workspace and depend on `:sdk:client` from a new
   **`light`** product flavor (alongside `browser`/`purist`/`cloud`).
2. Reshape the screen layer onto `LightScreen`/`LightScreenViewModel`, keeping the
   existing `SomaViewModel` and domain/storage modules unchanged.
3. Run the SDK emulator and the allowlist checks, then package for ADB sideload; pursue
   Light-signed distribution when the Tool Library opens.

## Sources

- LightOS Developer SDK — <https://github.com/lightphone/light-sdk>
- "The minimalist Light Phone III will soon support third-party apps" — Engadget
- "Light Phone III Third-Party Apps Explained: Rules and Risks" — Gadget Hacks
