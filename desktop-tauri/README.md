# Caltrain Auto Login desktop tray app

A Tauri **2.12.5** tray application with a Rust login client and an on-demand
settings window. The background helper creates no WebView at autostart.
Windows uses native network notifications and snapshots;
Ubuntu uses NetworkManager's `nmcli`. Android remains Kotlin.

Automatic sign-in has been tested on Windows 11.
Run only one Caltrain sign-in helper at a time when testing on the train.

Use a **release** build for normal use. Debug executables retain a Windows
console window for diagnostics. In the unreleased settings change, opening the
Caltrain Auto Login shortcut shows settings; start-at-login stays in the system tray.
Right-click the tray icon for connect, refresh, settings and quit.
The tray has a separate transparent, tightly framed train icon; launcher icons
keep the circular background.

The source now uses **Caltrain Auto Login** for its display name. The application
identifier, executable name, settings location and existing OS startup
registration name remain unchanged. A Windows installer upgrade from the old
product name still needs validation before distribution.

## Settings and tray controls

Open **Settings...** from the tray, or launch the shortcut again. One settings
window contains consent, automatic sign-in, start-at-login, updates, help and
activity-log actions. Window creation runs on a serialized worker so native
event callbacks remain nonblocking. Reopening focuses the existing window. Closing it destroys
the WebView and leaves the native helper running; **Quit** in the tray exits.

New profiles have consent and automatic sign-in turned off. Enable **Accept
Wi-Fi terms on my behalf**, then **Automatic sign-in**. **Connect now** is
available only when an eligible Caltrain connection is detected. Revoking
consent also turns automatic sign-in off. Changes save immediately, and failed
writes are shown in the window. The page uses explicit values instead of toggle
commands, so repeated requests cannot invert a preference.

**Start at login** is a separate, opt-in OS setting. The screen reads the actual
OS registration and displays errors rather than treating an unreadable value as
off. **Refresh status** requests a fresh network snapshot.

The settings page is bundled HTML/CSS/JavaScript with no frontend framework,
remote resources or runtime Node dependency. Its local-only capability grants
five typed app commands, with no general shell, filesystem or plugin access.
Status polling runs only while the window is open.

The app matches `Caltrain_WiFi` and numeric Windows profile suffixes. Windows
requires a connected physical Wi-Fi adapter with an IPv4 address and pauses
when another interface reports Internet access. Local-only Tailscale does not
block it. Ubuntu checks the active Wi-Fi SSID and the default route selected by
`ip route get`. HTTP requests bind to the selected Wi-Fi IPv4 address, disable
proxies and redirects, and use a fresh cookie jar for every attempt. DNS still
uses the OS resolver; VPN DNS policies can therefore affect portal resolution.

Unreleased diagnostics check that the selected Wi-Fi address can be bound
locally before starting requests. This sends no packets and does not establish
whether a VPN or firewall will permit traffic. DNS, access-denied, unavailable
address, timeout and connection failures have distinct guidance. No VPN brand
is inferred from these errors, and the helper cannot override VPN policy.

**VPN & connection help** opens a bundled local text file with troubleshooting
steps and product examples. It does not create another WebView.
Unlike Android's explicit per-network DNS lookup, desktop DNS uses the OS
resolver. A source-address bind and disabling HTTP proxies do not guarantee VPN
bypass. The normal OS captive-portal page remains a fallback.

Network events trigger checks, with a 60-second OS refresh as a fallback.
Failures retry after 60 seconds, backing off to five minutes. Successful
connections are rechecked after five minutes while the same Caltrain connection
remains eligible. Reconnects reset the delay; repeated identical events do not.
Revoking consent or losing network eligibility cancels the attempt. A replacement
waits for the previous worker to stop.

The portal flow validates its configuration, submits once, loads the fixed
Caltrain final page, and verifies Internet access for up to 45 seconds plus the
last request (each request has an eight-second limit). A successful portal
response alone is not reported as Internet access.

Preferences are saved atomically in `settings.json` under the OS local app data
directory for `dev.brian.caltrainwifi.desktop`. **Open activity log** opens
`events.jsonl`; it rotates at approximately 256 KB and retains one previous
file. Logs contain status and network names/addresses, not portal tokens or
response bodies. A corrupt settings file starts with sign-in disabled.
Unreleased network-failure records include the sign-in step, hostname, typed
failure category and a bounded error chain, with HTTP URLs omitted. Failed
final-page requests and Internet probes are recorded even when the protocol
continues; consecutive identical failures within an attempt are suppressed.
These diagnostic changes still need live train/VPN testing.

## Build and verify

Install Rust and [Tauri's platform prerequisites](https://v2.tauri.app/start/prerequisites/).
Direct Tauri/plugin versions and `Cargo.lock` are pinned. There is no frontend
package or Node runtime in the installed app; npm is only used for the build CLI.

```sh
cargo fmt --check
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
python -m unittest discover -s tools -p 'test_*.py'
node --test tools/test-settings-ui.cjs
cargo build --release --locked
```

Windows per-user NSIS installer. The unreleased settings page requires WebView2;
installer configuration now downloads the Microsoft bootstrapper when WebView2
is missing. This path needs network access and has not been tested on Windows.
No public installer has been published:

```sh
npm exec --yes --package=@tauri-apps/cli@2.12.4 -- tauri build --ci -- --locked
```

Ubuntu package (requires NetworkManager and `iproute2` at runtime):

```sh
npm exec --yes --package=@tauri-apps/cli@2.12.4 -- tauri build --ci --bundles deb -- --locked
```

Use `CALTRAIN_PROFILE` to select an isolated test profile. The diagnostic
argument `--diagnostic-seconds 8` exits after eight seconds and does not enable
consent. Windows native snapshot comparison:

```powershell
.\tools\check-network.ps1 -ExecutablePath .\target\release\caltrain-wifi.exe
```

On an Ubuntu desktop with non-Caltrain Wi-Fi, `python3-dbus` and `python3-gi`:

```sh
dbus-run-session -- /usr/bin/python3 tools/smoke-linux.py ./target/debug/caltrain-wifi
```

This exercises the exported native tray quick actions, quit and restart under
an isolated profile/session bus. To test real settings IPC, preference persistence,
revocation, closing without quitting, second launches and window reuse, add
`--settings-ui` with `python3-xlib` and Pillow on a **private Xvfb display**:

```sh
DISPLAY=:87 GDK_BACKEND=x11 LIBGL_ALWAYS_SOFTWARE=1 WEBKIT_DISABLE_COMPOSITING_MODE=1 \
  dbus-run-session -- /usr/bin/python3 tools/smoke-linux.py ./target/debug/caltrain-wifi --settings-ui
```

The UI test sends pointer events on that display and saves `settings.png` in its
temporary profile. Neither smoke mode changes start-at-login.
macOS network monitoring is not implemented; that platform stays ineligible.

## Updates and release preparation

The checked-in configuration has no update endpoint or public key. Its updater
controls are disabled. Nothing has been published. Production signing, feed publication and device upgrade testing remain pending.

Configured builds check for updates after 30 seconds and every six hours, unless
**Check for updates automatically** is unchecked. An available update changes the
settings button to **Install update <version>**. Installation requires that click for the displayed candidate, verifies
the signature and signed version, stops any login worker, and restarts the app.
Failures restore normal sign-in operation. This is separate from Windows
Authenticode signing, so an updater-signed installer can still trigger SmartScreen.

The manual `Prepare Caltrain Windows package` GitHub workflow builds downloadable
workflow artifacts only. It never publishes releases. Its default unsigned mode
builds a normal installer with the updater disabled. Signed mode requires:

- A persistent signing key in the `TAURI_SIGNING_PRIVATE_KEY` repository secret,
  with `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` if password-protected.
- The Tauri `.pub` file contents in repository variable `TAURI_UPDATER_PUBLIC_KEY`.
- The `release_repository` workflow input: `Escher0/caltrain-auto-login`.

Keep the private key backed up outside Git. Do not generate a different key for
each release. See [Tauri's updater instructions](https://v2.tauri.app/plugin/updater/).
Local metadata preparation uses only the public key:

```sh
python tools/release.py config --repository Escher0/caltrain-auto-login --version 0.2.1 --public-key /path/to/key.pub --output /tmp/release.json
# Set the signing-key environment variables for this build.
npm exec --yes --package=@tauri-apps/cli@2.12.4 -- tauri build --ci --config /tmp/release.json -- --locked
python tools/release.py manifest --repository Escher0/caltrain-auto-login --version 0.2.1 --installer 'target/release/bundle/nsis/Caltrain Auto Login_0.2.1_x64-setup.exe' --output /tmp/latest.json
```

The **Windows release** workflow prepares signed drafts and separately publishes
reviewed releases. Windows reads `windows/stable.json` on the `updates` branch,
independently of Android release tags. See the [release guide](../docs/releases.md)
for signing setup, preparation, publication, recovery and remaining device checks.
Change Cargo.toml, Cargo.lock and tauri.conf.json together before the next build.
Ubuntu `.deb` updates remain manual; the manifest targets Windows x64 only.

See [licensing](../LICENSING.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
