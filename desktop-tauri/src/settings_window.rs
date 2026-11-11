use crate::{open_text, updates, Lifecycle};
use caltrain_wifi::{controller::View, runtime::Preference};
use serde::{Deserialize, Serialize};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    mpsc::{self, SyncSender},
    Arc,
};
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_autostart::ManagerExt;

#[derive(Serialize)]
pub struct SettingsView {
    login: View,
    startup: Option<bool>,
    startup_error: Option<String>,
    updates: updates::View,
    version: String,
}

fn local_settings_url(url: &tauri::Url) -> bool {
    // Tauri's initial Linux URL has an empty path. Permit only the bundled
    // document on the platform-specific app origin, never external pages.
    matches!(
        (url.scheme(), url.host_str()),
        ("tauri", Some("localhost")) | ("http" | "https", Some("tauri.localhost"))
    ) && matches!(url.path(), "" | "/" | "/index.html")
}

// WebviewWindowBuilder must not run in synchronous event handlers on Windows.
// One worker owns all check/focus/create operations, so queued tray and
// second-instance requests cannot race to create duplicate windows.
pub struct Opener {
    requests: SyncSender<()>,
    stopped: Arc<AtomicBool>,
}
impl Opener {
    pub fn start(app: AppHandle) -> std::io::Result<Self> {
        Self::spawn(move || {
            if let Err(error) = open(&app) {
                if let Some(state) = app.try_state::<Lifecycle>() {
                    state.log.write(
                        "settings-window-error",
                        serde_json::json!(error.to_string()),
                    );
                    state.login.send(caltrain_wifi::runtime::Command::Notice(
                        "Could not open settings".into(),
                    ));
                }
            }
        })
    }
    fn spawn(mut open: impl FnMut() + Send + 'static) -> std::io::Result<Self> {
        let (requests, pending) = mpsc::sync_channel(1);
        let stopped = Arc::new(AtomicBool::new(false));
        let worker_stop = stopped.clone();
        std::thread::Builder::new()
            .name("settings-window".into())
            .spawn(move || {
                while pending.recv().is_ok() {
                    if worker_stop.load(Ordering::Acquire) {
                        break;
                    }
                    open();
                }
            })?;
        Ok(Self { requests, stopped })
    }
    pub fn request(&self) {
        // Coalesce repeated opens without ever waiting on the UI event thread.
        let _ = self.requests.try_send(());
    }
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::Release);
        // Wake an idle worker. A full queue already supplies its wake-up.
        let _ = self.requests.try_send(());
    }
}
impl Drop for Opener {
    fn drop(&mut self) {
        self.stop();
    }
}

fn open(app: &AppHandle) -> tauri::Result<()> {
    if let Some(window) = app.get_webview_window("settings") {
        window.unminimize()?;
        window.set_focus()?;
        return Ok(());
    }
    WebviewWindowBuilder::new(app, "settings", WebviewUrl::App("index.html".into()))
        .title("Caltrain Auto Login settings")
        .inner_size(660.0, 850.0)
        .min_inner_size(420.0, 550.0)
        .on_navigation(local_settings_url)
        .build()?;
    // Default close destroys the window and its WebView. Do not hide it.
    Ok(())
}

#[tauri::command]
pub fn settings_state(app: AppHandle, state: tauri::State<'_, Lifecycle>) -> SettingsView {
    let startup = app.autolaunch().is_enabled();
    SettingsView {
        login: state.login.view.borrow().clone(),
        startup: startup.as_ref().ok().copied(),
        startup_error: startup
            .err()
            .map(|error| format!("Could not read start-at-login status: {error}")),
        updates: state.updater.view(),
        version: app.package_info().version.to_string(),
    }
}

#[tauri::command]
pub async fn set_preference(
    state: tauri::State<'_, Lifecycle>,
    preference: Preference,
    enabled: bool,
) -> Result<(), String> {
    state.login.set_preference(preference, enabled).await
}

#[tauri::command]
pub fn set_startup(
    app: AppHandle,
    state: tauri::State<'_, Lifecycle>,
    enabled: bool,
) -> Result<(), String> {
    let manager = app.autolaunch();
    let result = if enabled {
        manager.enable()
    } else {
        manager.disable()
    };
    result.map_err(|error| format!("Could not change start at login: {error}"))?;
    let actual = manager
        .is_enabled()
        .map_err(|error| format!("Could not verify start at login: {error}"))?;
    state.log.write("startup", serde_json::json!(actual));
    if actual != enabled {
        return Err("Start-at-login status did not match the requested setting".into());
    }
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    Connect,
    Refresh,
    Activity,
    Help,
}

#[tauri::command]
pub fn settings_action(state: tauri::State<'_, Lifecycle>, action: Action) -> Result<(), String> {
    match action {
        Action::Connect => state.login.send(caltrain_wifi::runtime::Command::Connect),
        Action::Refresh => state.refresh(),
        Action::Activity => open_text(&state.log.directory.join("events.jsonl"))
            .map_err(|error| format!("Could not open activity log: {error}"))?,
        Action::Help => {
            let path = state.log.directory.join("connection-help.txt");
            std::fs::write(&path, include_str!("connection-help.txt"))
                .and_then(|()| open_text(&path))
                .map_err(|error| format!("Could not open connection help: {error}"))?;
        }
    }
    Ok(())
}

#[tauri::command]
pub fn request_update(state: tauri::State<'_, Lifecycle>, candidate: Option<u64>) {
    state.updater.request_candidate(candidate);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_requests_return_without_waiting_and_use_one_background_worker() {
        use std::{sync::mpsc, thread, time::Duration};
        let caller = thread::current().id();
        let (entered, calls) = mpsc::channel();
        let (release, wait) = mpsc::channel();
        let mut attempt = 0;
        let opener = Opener::spawn(move || {
            attempt += 1;
            entered.send((thread::current().id(), attempt)).unwrap();
            if attempt == 1 {
                wait.recv_timeout(Duration::from_secs(2)).unwrap();
            }
        })
        .unwrap();
        opener.request();
        let (worker, first) = calls.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_ne!(
            worker, caller,
            "Window creation must not run on the caller's event thread"
        );
        assert_eq!(first, 1);
        // These requests must neither wait on the busy worker nor launch a
        // second worker. A bounded pending slot coalesces repeated opens.
        for _ in 0..20 {
            opener.request();
        }
        assert!(calls.try_recv().is_err());
        release.send(()).unwrap();
        assert_eq!(
            calls.recv_timeout(Duration::from_secs(2)).unwrap(),
            (worker, 2)
        );
        assert!(calls.try_recv().is_err());
        opener.stop();
    }

    #[test]
    fn navigation_accepts_initial_bundled_url_and_blocks_remote_content() {
        for url in [
            "tauri://localhost",
            "tauri://localhost/index.html",
            "http://tauri.localhost/",
        ] {
            assert!(local_settings_url(&url.parse().unwrap()), "{url}");
        }
        for url in [
            "https://example.com/",
            "http://localhost/",
            "tauri://localhost/other.html",
            "https://tauri.localhost.example.com/",
        ] {
            assert!(!local_settings_url(&url.parse().unwrap()), "{url}");
        }
    }
}
