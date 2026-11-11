# Caltrain Auto Login for Android

Native Kotlin app for Android 10 or newer (minimum API 29, target API 36).
Automatic sign-in has been tested on a Pixel phone. This repository contains
source; no public APK release is available yet.

## Use

After installing a build, join Caltrain Wi-Fi in Android settings. Open the app,
authorize acceptance of the Wi-Fi terms and enable automatic sign-in. **Connect
now** starts a manual attempt. Revoking consent disables automatic mode and
cancels work. No browser, clipboard access or persistent notification is required.

The screen separates current Wi-Fi status from historical activity. A portal
accepting sign-in is not itself proof of Internet access. Without network-name
identification, an online connection is described generically, not as Caltrain.

## Optional network identification

On Android 12+, **Identify Caltrain Wi-Fi** requests precise location permission;
Android also requires Location to be enabled to reveal the network name. This
app reads the connected Wi-Fi name, not GPS coordinates or nearby network scans.
Optional background identification opens Android settings for an additional grant.

- A readable `Caltrain_WiFi` name permits sign-in; a different name skips it.
- Denied, approximate-only or unavailable location permission, Location off, and
  hidden network names fall back to the original portal checks.
- Android 10/11 use those original checks without offering network identification.

Unknown names do not authorize arbitrary portals: the app still checks the fixed
HTTPS Caltrain portal and its expected configuration before submitting anything.

## Background behavior

Wi-Fi captive-portal events queue a short Android job. The listener is renewed
after attempts; a 15-minute OS-managed maintenance job repairs registration.
Maintenance itself sends no network requests. Registration is restored at launch,
reboot and app update. Android can delay execution. Reopen the app after
force-stopping it to restore automatic mode.

DNS and sockets bind to the selected Wi-Fi network. Cellular access cannot satisfy
the portal connection check. Network loss and revoked consent stop attempts.

## VPN and DNS help

Android can deny direct Wi-Fi access when a VPN or filtering app disallows bypass.
Use that app's per-app exclusion or split-tunneling controls. For example:

- AdGuard: exclude this app from traffic routing, not just content filtering.
- Tailscale: exclude this app using app-based split tunneling.

Android's **Block connections without VPN** can still prevent excluded apps from
connecting. A configured Private DNS provider may also be unreachable before
portal sign-in. The helper cannot change these policies. It checks socket-binding
permission before DNS and reports distinct access, DNS and connection failures.
The normal Android sign-in page remains a fallback.

## Updates and signing

The checked-in update repository is unset, so checks are disabled and send no
requests. A release build can configure the public repository with
`-PcaltrainUpdateRepository=Escher0/caltrain-auto-login`.

Configured builds support manual checks, optional app-open checks at most daily,
and a queued check after an eligible failed sign-in at most every 12 hours.
Checks use a validated default Internet connection, which may be cellular or a
VPN, rather than the captive Wi-Fi session. Android may defer the job. Optional
notification permission enables update alerts; updates also appear in the app.

The checker selects stable releases tagged `caltrain-android-vMAJOR.MINOR.PATCH`
with a nonempty `caltrain-wifi-android-MAJOR.MINOR.PATCH.apk` asset. It opens the
release page for the user to download and install; it does not install silently.

Production signing and a device upgrade test are pending. Development builds use
a local debug key. Android requires a compatible signing identity for in-place
updates. Builds signed by another key require uninstalling the existing app,
which clears its settings. Never commit signing keys or `local.properties`.

## Build and test

Open `android/` in Android Studio. Keep the adjacent `shared/` directory.
Install JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME`
to the SDK directory or use Android Studio's local SDK configuration.

From this directory on Linux/macOS:

```sh
./gradlew :shared:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat` instead. The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

Tests use synthetic fixtures, local HTTP servers and Robolectric. They do not
sign into Caltrain. Current source includes the renamed app, VPN diagnostics and
update checks; these changes still need device-level release validation.
The application ID remains `dev.brian.caltrainwifi`.

## Privacy

Consent, preferences and recent activity are stored locally. No analytics or
remote logging are included. Portal sessions are not saved between attempts.
Settings are excluded from Android backup/device transfer. Diagnostic messages
omit request bodies, cookies, URL paths and query strings. Update checks contact
GitHub only when configured and requested or enabled.

See [artwork](ARTWORK.md), [the protocol](../PROTOCOL.md) and
[licensing](../LICENSING.md).
