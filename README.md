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

This first public snapshot is source-only. Installers and APK releases have not
been published. The latest settings, diagnostic and update-checking changes still
need device-level release validation.

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

Update-checking code is included, but the checked-in configuration has no live
feed or signing key. Public release signing and upgrade tests are still pending.
Creating this source repository does not enable automatic app updates.

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
