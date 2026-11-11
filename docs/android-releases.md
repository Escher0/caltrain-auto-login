<!-- SPDX-FileCopyrightText: 2026 Brian Pilnick -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Additional WebView2 permission: see LICENSING.md at the repository root. -->

# Android releases

Android keeps its existing native Kotlin app and GitHub release-page updater.
The **Android release** workflow builds signed APKs from main as drafts. A
separate `publish` operation makes a reviewed APK discoverable to installed apps.
It does not modify the Windows update feed or install anything on a phone.

## Signing setup (once)

Create a dedicated release keystore with Android Studio's **Generate Signed
Bundle / APK** wizard or Android's [signing tools](https://developer.android.com/studio/publish/app-signing).
Use the same release key for every APK. Back up the keystore, alias and passwords
outside Git before using it; GitHub secrets cannot be downloaded again.

Do not reuse a development/debug key for public releases. The application ID
remains `dev.brian.caltrainwifi`. An existing development installation signed
with another key must be uninstalled before the first public APK can be installed,
which clears settings. After that one-time migration, same-key releases support
normal in-place updates. Do not rotate the key casually.

Configure these repository values:

| Type | Name | Value |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_BASE64` | Keystore encoded as base64 on one line |
| Secret | `ANDROID_STORE_PASSWORD` | Keystore password |
| Secret | `ANDROID_KEY_ALIAS` | Signing entry alias |
| Secret | `ANDROID_KEY_PASSWORD` | Signing entry password |
| Variable | `ANDROID_SIGNING_CERT_SHA256` | SHA-256 digest of the DER signing certificate, 64 lowercase hex characters without colons |

For example, `keytool -exportcert` exports the certificate; hash its binary
output with SHA-256. This is the **certificate** digest, not the keystore file
digest. `apksigner verify --print-certs app.apk` prints it for an existing signed
APK. Private keys/passwords must never be committed or pasted into release notes.

Local signing uses the environment variables `CALTRAIN_KEYSTORE` (absolute
keystore path), `CALTRAIN_STORE_PASSWORD`, `CALTRAIN_KEY_ALIAS` and
`CALTRAIN_KEY_PASSWORD`. `-PcaltrainRelease=true` requires complete credentials
and `-PcaltrainUpdateRepository=Escher0/caltrain-auto-login`. Normal development
builds retain an unset updater repository. No production keystore is included.

## Prepare and review

1. Increment both `versionName` and `versionCode` in
   `android/app/build.gradle.kts`; use stable `major.minor.patch` names. Merge
   reviewed changes to main.
2. Run **Android release** on **main**, with that version and `prepare`.
3. The workflow runs shared, debug and release tests, release lint and packaging.
   It verifies the APK signature, certificate, application ID, version and
   non-debuggable state. Compared with every previous stable Android release,
   both version and version code must increase and the certificate must match.
4. Review the draft `caltrain-android-vVERSION`, update its release notes, and
   test installation plus an upgrade from the previous release on a phone.

The APK is named `caltrain-wifi-android-VERSION.apk` to match the existing app's
updater. This filename is intentionally retained despite the display-name change.
`android-release.json` records its SHA-256 hash, certificate digest, identity and
versions. Source ZIP, licenses and dependency notices are attached. License texts
are also included under `assets/licenses/` inside the APK.

Dependency notices are generated from resolved release-runtime coordinates.
The generator checks their Maven license declarations, preserves upstream
license/NOTICE files and attaches source JARs, POMs and a source checksum list.
It stops for a license other than the currently used Apache-2.0 rather than
silently claiming coverage. Sources are fetched from Maven Central over HTTPS.
Review that report when dependencies change.

## Publish

Run **Android release** on **main** with the reviewed version and `publish`.
The workflow downloads the draft APK and verifies its actual signature and
metadata again, rechecks the upgrade rules, and publishes the release. Repeating
publication is safe for an unchanged release that has not been superseded.
Do not modify draft assets after device review or replace already-published APKs.

The app ignores drafts, prereleases and desktop tags. It selects the highest
compatible stable Android version from the GitHub release list, then opens the
release page. Downloading/installing requires user action and Android's normal
sideloading confirmation. Existing manual, optional daily app-open and
failure-triggered (at most every 12 hours) checks remain unchanged.

If preparation fails after creating its tag or draft, inspect both before
removing/recreating them. Existing published Android releases without
`android-release.json` require an explicit metadata migration before this
pipeline can establish their version code and signing identity. It never guesses
those values or skips an unknown earlier release.

## CI and first-release readiness

**Android checks and package** uses a new disposable signing key for each run.
It receives no production signing secrets and uploads artifacts labelled
`android-ci-test-only`. Those APKs are build tests, not updates for existing
installations. CI also verifies that production mode rejects missing credentials.

Before the first public release, the persistent key must be configured/backed up
and device installation/update behavior must be checked. CI validates the build
and APK; it does not prove phone installation, background sign-in, or that a user
has granted sideloading permission. Play Store distribution is separate from
this GitHub APK workflow.
