# Caltrain Auto Login

Automatically sign in to Caltrain's onboard Wi-Fi after your device joins the
network. An unofficial, personal open-source helper. Not affiliated with Caltrain.

The app accepts the captive portal terms only after you authorize it. It checks
Internet access separately from the portal's authentication response.

## Platforms

| Platform | Implementation | Status |
| --- | --- | --- |
| Windows | Tauri tray app with native network notifications and a settings window | Automatic sign-in tested on Windows 11 |
| Android | Native Kotlin app with Wi-Fi event listeners | Automatic sign-in tested on a Pixel phone |
| Ubuntu | Tauri tray app using NetworkManager | Local checks; live train testing still needed |
| macOS | Not implemented | No network monitor yet |

## Get started

**Android:** Download the APK from [Android 0.1.6](https://github.com/Escher0/caltrain-auto-login/releases/tag/caltrain-android-v0.1.6).
Installation and sign-in were tested on a Pixel 11 Pro with VPN bypass enabled.
If replacing a development build, follow the release notes for the one-time
uninstall/reinstall migration.

**Windows:** A public installer is not available yet. Windows Smart App Control
blocked the unsigned release candidate; public-trust code signing is still needed.

- [Desktop setup, building and testing](desktop-tauri/README.md)
- [Android setup, permissions and building](android/README.md)
- [Portal protocol](PROTOCOL.md)
- [Shared protocol fixtures](shared/README.md)

Once installed, join Caltrain Wi-Fi using your device's normal settings. Open
Caltrain Auto Login, authorize acceptance of the Wi-Fi terms, and enable automatic
sign-in. On desktop, start-at-login is a separate option. Android background work
can be delayed by the OS; a persistent notification is not used.

## VPNs and network access

A VPN or filtering app can prevent access to the captive portal. If needed,
exclude Caltrain Auto Login using that app's per-app routing settings. AdGuard
and Tailscale are examples. The helper cannot override VPN policy, a kill switch
or Android's block-without-VPN setting. See the in-app VPN help and platform docs.

## Updates

The Android release checks GitHub for newer stable Android versions. Enable
automatic checks and notification permission in **Updates** to receive alerts.
Downloading and installing an update require your confirmation. Public update
detection and a same-key upgrade with settings retained were verified in an
Android emulator.

Windows release/update validation is still pending. Development builds on both
platforms leave the update source unconfigured unless explicitly set at build time.

## Development

- `android/`: Android app and Gradle wrapper.
- `shared/`: Kotlin portal client and synthetic fixtures shared with Rust tests.
- `desktop-tauri/`: Rust desktop app, local settings UI and release tools.
- `design/`: Icon source artwork and saved revisions.

The first public commit is a standalone source snapshot. Earlier experiments and
private development history are not part of this repository. Existing application
IDs are retained for continuity with development builds.

When reporting a problem, include the platform, app version and relevant activity
messages. Remove network names, local IP addresses and other personal information
before posting logs. Do not post portal captures, cookies or tokens.

## License

Original project material is [GPL-3.0-or-later](LICENSE), with a narrow additional
permission for Microsoft WebView2. See [LICENSING.md](LICENSING.md) and
[third-party notices](THIRD_PARTY_NOTICES.md).
