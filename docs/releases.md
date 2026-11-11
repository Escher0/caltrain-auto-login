<!-- SPDX-FileCopyrightText: 2026 Brian Pilnick -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Additional WebView2 permission: see LICENSING.md at the repository root. -->

# Releases

Windows and Android have separate versions and release tags. A newer Android
release must not replace the Windows update feed. Neither workflow runs on a
version tag push, and ordinary builds keep updates disabled.

## Windows setup (once)

Generate a persistent key using Tauri's
[signer instructions](https://v2.tauri.app/plugin/updater/#signing-updates).
Store the private key and password in a backed-up password manager or another
secure backup outside Git before using them. GitHub secrets cannot be read back.
Losing this key prevents existing installations from trusting future updates.

Configure the repository:

| Type | Name | Value |
| --- | --- | --- |
| Secret | `TAURI_SIGNING_PRIVATE_KEY` | Contents of the private key file |
| Secret | `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | Key password, if any |
| Variable | `TAURI_UPDATER_PUBLIC_KEY` | Contents of the matching `.pub` file |

These are **updater** signatures. They do not provide Windows Authenticode
signing or remove SmartScreen's unrecognized-publisher warnings.

## Prepare Windows

1. Change `desktop-tauri/Cargo.toml`, `Cargo.lock` and `tauri.conf.json` to the
   same stable `major.minor.patch` version. Merge the reviewed changes to main.
2. Run **Windows release** on **main**, with that version and `prepare`.
3. The workflow tests the app, checks metadata, generates dependency notices,
   builds a per-user Windows x64 NSIS installer, verifies its signature against
   the configured public key and checks the signed version. It attaches the
   installer, signature, manifest, licenses and a source archive to a **draft**
   release tagged `caltrain-desktop-vVERSION`.
4. Review the draft's notes and license report. Test installation and upgrade
   before publishing. In particular, validate upgrade from the old
   **Caltrain Wi-Fi** product name and confirm preferences/startup survive.
   For the first public version, install its release-configured installer once;
   earlier development installations have no production update feed/key.

The source archive contains the exact Git commit and `cargo vendor --locked`
output, including dependency sources/notices and a `.cargo/config.toml` pointing
to them. The installer includes `licenses/`. The notice generator compares the
WebView2 loader with the pinned Microsoft SDK before including its license and
NOTICE text; changing that dependency requires reviewing these inputs again.

**Prepare Caltrain Windows package** remains available for build-only checks.
It also runs for desktop pull requests using read-only permissions and no
production signing credentials. Its artifacts are test packages, not releases.

## Publish Windows

Run **Windows release** on **main**, with the reviewed version and `publish`.
This is the deliberate step that makes the installer public and offers it to
existing users. It verifies release metadata and required assets, publishes the
release, then updates `windows/stable.json` on the `updates` branch:

`https://raw.githubusercontent.com/Escher0/caltrain-auto-login/updates/windows/stable.json`

The branch is created automatically on the first publication. It is a feed
branch, not a development branch. Keep it writable by this workflow. Updating
the feed checks its previous file SHA, so competing edits fail rather than
silently overwrite each other. GitHub/CDN caching may delay client visibility.

Publishing twice with identical metadata is safe. If the release becomes public
but the feed write fails, fix the reported permission/concurrency error and run
`publish` again. The helper rejects downgrades and different metadata at the same
version. Correct a bad published version with a new, higher patch release;
do not replace a released installer or rotate the key casually.

Preparation never overwrites an existing release. If a draft upload fails,
inspect the draft and tag before removing/recreating them; never delete a
published version to retry a build. Publishing a reviewed draft does not rebuild
its binaries. Do not edit its assets between review and publication.

## Client behavior and remaining checks

Windows checks after startup and periodically, and installing an available
update requires the user's confirmation. It verifies both the signature and
the version bound into that signature. Tests exercise the real Tauri signer
with disposable keys, including tampered bytes and a wrong version.

Before calling the first public release ready, provision/back up the signing
key, review generated notices, and test a real old-to-new installer/update on
Windows. CI packaging does not demonstrate those device-level checks. Ubuntu
and macOS release/update packaging is not included here.
