// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

use caltrain_wifi::settings::{Settings, Store};

#[test]
fn new_installs_have_no_consent_or_automatic_login() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::new(dir.path());
    let settings = store.load().unwrap();
    assert!(!settings.consent);
    assert!(!settings.automatic);
}
#[test]
fn settings_survive_restart_and_revocation_disables_automatic_login() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::new(dir.path());
    store
        .save(&Settings {
            consent: true,
            automatic: true,
            check_updates: false,
        })
        .unwrap();
    assert_eq!(
        Store::new(dir.path()).load().unwrap(),
        Settings {
            consent: true,
            automatic: true,
            check_updates: false
        }
    );
    store
        .save(&Settings {
            consent: false,
            automatic: true,
            check_updates: false,
        })
        .unwrap();
    let loaded = store.load().unwrap();
    assert!(!loaded.consent);
    assert!(!loaded.automatic);
}
#[test]
fn corrupt_or_mistyped_settings_do_not_silently_enable_login() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::new(dir.path());
    for text in [
        "broken",
        r#"{"consent":"true","automatic":true}"#,
        "[true,true,true]",
        "[true]",
        r#"{"consent":false,"consent":true,"automatic":true}"#,
    ] {
        std::fs::write(dir.path().join("settings.json"), text).unwrap();
        assert!(store.load().is_err());
    }
    std::fs::write(dir.path().join("settings.json"), r#"{"automatic":true}"#).unwrap();
    assert!(!store.load().unwrap().automatic);
}
#[test]
fn failed_save_does_not_replace_existing_settings() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::new(dir.path());
    std::fs::create_dir(dir.path().join("settings.json")).unwrap();
    assert!(store.save(&Settings::default()).is_err());
    assert!(dir.path().join("settings.json").is_dir());
}
