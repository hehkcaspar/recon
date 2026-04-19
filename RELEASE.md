# Release builds

Producing a signed production APK for Recon.

## One-time: generate the release keystore

An Android app's signing key is permanent — every future update on Play must be
signed with the same key. Generate it once, back it up somewhere durable, and
never commit it.

From the project root:

```bash
keytool -genkeypair -v \
  -keystore recon-release.keystore \
  -alias recon \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

`keytool` prompts for a store password, key password, and a distinguished
name. Use a password manager — losing the password means losing the ability
to ship updates.

The `.keystore` / `.jks` extension is already gitignored.

## One-time: wire signing into Gradle

Create `keystore.properties` in the project root (gitignored) with:

```properties
storeFile=recon-release.keystore
storePassword=<store password>
keyAlias=recon
keyPassword=<key password>
```

`app/build.gradle.kts` loads this file if present and attaches the resulting
`signingConfig` to the `release` build type. If the file is absent, the release
build runs to completion but produces an **unsigned** APK — useful for CI smoke
builds, useless for distribution.

## Building

```bash
./gradlew assembleRelease        # signed APK → app/build/outputs/apk/release/
./gradlew bundleRelease          # signed AAB → app/build/outputs/bundle/release/
```

Google Play requires an **AAB**; sideloaded installs take the APK.

## What the release build does differently from debug

- `isMinifyEnabled = true` — R8 shrinks and obfuscates the Kotlin/Java bytecode.
- `isShrinkResources = true` — unused resources (drawables, layouts, strings)
  are stripped from the packaged APK.
- ProGuard rules live in `app/proguard-rules.pro`. The only reflective surfaces
  that need keep rules are: kotlinx-serialization (`PendingBundle`), the
  `BundleWorker` class (WorkManager instantiates by name), the `ReconApp`
  Application class, and CameraX Extensions vendor shims.
- Release builds are implicitly **non-debuggable** (no `android:debuggable`
  attribute is set).

## Verifying a build

```bash
# Confirm the APK is signed and identify the key:
$ANDROID_HOME/build-tools/*/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk

# Inspect the final manifest (post-manifest-merge):
$ANDROID_HOME/build-tools/*/aapt2 dump badging \
  app/build/outputs/apk/release/app-release.apk | head -40
```

## Uploading to Play Console

1. First time only: create the app listing in Play Console, opt in to
   **Play App Signing** (strongly recommended — Google holds the upload
   key, you keep a separate deploy key).
2. Upload the AAB from `app/build/outputs/bundle/release/app-release.aab`.
3. On every release, bump `versionCode` (monotonic integer, never reused) and
   `versionName` (human-readable) in `app/build.gradle.kts`.
4. Upload the R8 `mapping.txt` alongside the AAB
   (`app/build/outputs/mapping/release/mapping.txt`) so Play can deobfuscate
   crash reports.

## Rotating the signing key

Don't. If you've enabled Play App Signing, Google can rotate the upload key
independently, but the app's actual distribution key is held by Google and
never needs to change.

If you somehow leak the upload key: Play Console → Setup → App integrity →
Request upload key reset.
