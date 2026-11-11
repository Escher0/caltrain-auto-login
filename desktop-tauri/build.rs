// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

fn main() {
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "settings_state",
            "set_preference",
            "set_startup",
            "settings_action",
            "request_update",
        ]),
    ))
    .expect("Could not build Caltrain Auto Login assets");
}
