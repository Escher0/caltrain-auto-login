// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

use crate::Log;
use caltrain_wifi::runtime::{Command, Handle as LoginHandle};
use std::{
    sync::{Arc, Mutex},
    time::Duration,
};
use tauri::AppHandle;
use tauri_plugin_updater::UpdaterExt;
use tokio_util::sync::CancellationToken;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Action {
    Check,
    Install(u64),
}
impl Action {
    fn matches_install(self, available: Option<u64>) -> bool {
        matches!(self, Self::Install(id) if Some(id) == available)
    }
}

#[derive(Clone, serde::Serialize)]
pub struct View {
    pub configured: bool,
    pub enabled: bool,
    pub label: String,
    pub candidate: Option<u64>,
}
impl Default for View {
    fn default() -> Self {
        Self {
            configured: false,
            enabled: false,
            label: "Updates unavailable in this build".into(),
            candidate: None,
        }
    }
}

pub struct Handle {
    view: Arc<Mutex<View>>,
    sender: tokio::sync::mpsc::Sender<Action>,
}
impl Handle {
    pub fn view(&self) -> View {
        self.view.lock().unwrap().clone()
    }
    pub fn request_candidate(&self, candidate: Option<u64>) {
        let _ = self
            .sender
            .try_send(candidate.map_or(Action::Check, Action::Install));
    }
}

pub fn configured(app: &AppHandle) -> bool {
    app.config().plugins.0.get("updater").is_some_and(|config| {
        config["pubkey"].as_str().is_some_and(|key| !key.is_empty())
            && config["endpoints"]
                .as_array()
                .is_some_and(|urls| !urls.is_empty())
    })
}

pub fn start(app: AppHandle, login: LoginHandle, log: Log, stop: CancellationToken) -> Handle {
    let (sender, mut actions) = tokio::sync::mpsc::channel(1);
    let update_view = Arc::new(Mutex::new(View::default()));
    let handle = Handle {
        view: update_view.clone(),
        sender,
    };
    if !configured(&app) {
        return handle;
    }
    *update_view.lock().unwrap() = View {
        configured: true,
        enabled: true,
        label: "Check for updates".into(),
        candidate: None,
    };
    tauri::async_runtime::spawn(async move {
        let mut view = login.view.clone();
        let mut available: Option<(u64, tauri_plugin_updater::Update)> = None;
        let mut candidate_id = 0;
        let mut next_check = tokio::time::Instant::now() + Duration::from_secs(30);
        loop {
            let enabled = view.borrow().settings.check_updates;
            let requested = tokio::select! {
                _=stop.cancelled()=>return,
                action=actions.recv()=>if action.is_some(){action}else{return;},
                changed=view.changed()=>{if changed.is_err(){return;}continue;},
                _=tokio::time::sleep_until(next_check),if enabled=>None,
            };
            let install = requested.is_some_and(|action| {
                action.matches_install(available.as_ref().map(|(id, _)| *id))
            });
            if matches!(requested, Some(Action::Install(_))) && !install {
                continue;
            }
            let manual = requested.is_some();
            next_check = tokio::time::Instant::now() + Duration::from_secs(6 * 60 * 60);
            *update_view.lock().unwrap() = View {
                configured: true,
                enabled: false,
                candidate: None,
                label: if install {
                    "Downloading update..."
                } else {
                    "Checking for updates..."
                }
                .into(),
            };
            if install {
                let (_, update) = available.take().unwrap();
                let downloaded = tokio::select! {_=stop.cancelled()=>return,result=update.download(|_,_|{},||{})=>result};
                match downloaded {
                    Ok(bytes) => {
                        log.write("update-verified", serde_json::json!(update.version));
                        update_view.lock().unwrap().label = "Installing update...".into();
                        if login.pause().await.is_err() {
                            return;
                        }
                        // pause acknowledges only after the sign-in worker has
                        // stopped, so installation cannot interrupt a request.
                        if stop.is_cancelled() {
                            return;
                        }
                        match update.install(bytes) {
                            Ok(()) => {
                                log.write("update-installed", serde_json::json!(update.version));
                                app.restart();
                            }
                            Err(_) => {
                                log.write("update-error", serde_json::json!("Install failed"));
                                login.send(Command::Resume);
                                update_view.lock().unwrap().label =
                                    "Update failed; check again".into();
                            }
                        }
                    }
                    Err(_) => {
                        // The next settings button says "check", so it must not install
                        // a cached candidate from an earlier successful check.
                        available = None;
                        log.write(
                            "update-error",
                            serde_json::json!("Download or signature verification failed"),
                        );
                        update_view.lock().unwrap().label = "Update failed; check again".into();
                    }
                }
            } else {
                let check = async {
                    app.updater_builder()
                        .timeout(Duration::from_secs(30))
                        .build()?
                        .check()
                        .await
                };
                let result = tokio::select! {_=stop.cancelled()=>return,result=check=>result};
                match result {
                    Ok(Some(update)) => {
                        candidate_id += 1;
                        update_view.lock().unwrap().label =
                            format!("Install update {}", update.version);
                        available = Some((candidate_id, update));
                    }
                    Ok(None) => {
                        available = None;
                        update_view.lock().unwrap().label = "Up to date; check again".into();
                    }
                    Err(_) => {
                        available = None;
                        update_view.lock().unwrap().label = if manual {
                            "Update check failed; try again"
                        } else {
                            "Check for updates"
                        }
                        .into();
                    }
                }
            }
            let mut view = update_view.lock().unwrap();
            view.enabled = true;
            view.candidate = available.as_ref().map(|(id, _)| *id);
        }
    });
    handle
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_requests_preserve_the_candidate_the_user_saw() {
        let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
        let handle = Handle {
            sender,
            view: Arc::new(Mutex::new(View::default())),
        };
        handle.request_candidate(None);
        assert_eq!(receiver.try_recv().unwrap(), Action::Check);
        handle.request_candidate(Some(1));
        assert_eq!(receiver.try_recv().unwrap(), Action::Install(1));
    }

    #[test]
    fn queued_check_click_cannot_become_install_after_check_completes() {
        let first_click = Action::Check;
        let queued_second_click = Action::Check;
        assert!(!first_click.matches_install(None));
        assert!(!queued_second_click.matches_install(Some(1)));
    }

    #[test]
    fn install_requires_the_candidate_shown_when_clicked() {
        let click = Action::Install(1);
        assert!(click.matches_install(Some(1)));
        assert!(!click.matches_install(None));
        assert!(!click.matches_install(Some(2)));
    }
}
