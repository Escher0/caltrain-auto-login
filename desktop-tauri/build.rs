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
