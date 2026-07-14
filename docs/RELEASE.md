# Release process

## One-time: create the stable signing identity

The 1.0 contract replaces the preview debug identity with stable keys before
users are asked to trust Soma with long-lived data. Generate the keystore
yourself and keep it out of git (both paths are already gitignored):

```
keytool -genkeypair -v \
  -keystore release/soma-release.jks \
  -alias soma -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` at the repository root:

```
storeFile=release/soma-release.jks
storePassword=…
keyAlias=soma
keyPassword=…
```

Back both files up with the same care as an encrypted Soma backup: losing the
keystore permanently orphans every installed release build. Without these
files `assembleRelease` still builds unsigned, so CI needs no secrets.

## Every preview release

1. Land the cycle on `test` with CI green.
2. Bump `versionCode`/`versionName` in one `Release x.y.z-preview.N` commit
   and date the `Unreleased` CHANGELOG section.
3. Tag `v<versionName>` and push; wait for CI green on the tag's commit.
4. `./gradlew :app:assembleCloudPreview`, verify badging with `aapt dump
   badging` (versionCode gotcha: if it looks stale, rebuild with
   `--rerun-tasks`).
5. Rename to `Soma-<versionName>-cloud.apk`, compute SHA-256, and publish a
   pre-release titled `Soma <version> Preview <N>` with the prose + bullets +
   verification line + SHA-256 notes format.

## Stable releases

Same flow with `assembleRelease` (signed via `keystore.properties`), the
release published as Latest rather than pre-release, and the physical Light
Phone III performance measurements from `docs/PERFORMANCE.md` recorded in the
notes.
