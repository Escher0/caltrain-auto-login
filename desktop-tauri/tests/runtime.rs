// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

use caltrain_wifi::{
    controller::{Attempt, Outcome, View},
    network::{evaluate, Profile},
    runtime::*,
    settings::{Settings, Store},
};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

struct TestRunner(mpsc::UnboundedSender<(Attempt, oneshot::Sender<Outcome>)>);
impl Runner for TestRunner {
    async fn run(
        &self,
        attempt: Attempt,
        cancel: CancellationToken,
        _progress: Progress,
    ) -> Outcome {
        let (send, receive) = oneshot::channel();
        self.0.send((attempt, send)).unwrap();
        tokio::select! { _=cancel.cancelled()=>Outcome::Cancelled, result=receive=>result.unwrap() }
    }
}
async fn state(view: &mut watch::Receiver<View>, predicate: impl Fn(&View) -> bool) {
    tokio::time::timeout(Duration::from_secs(2), async {
        while !predicate(&view.borrow()) {
            view.changed().await.unwrap();
        }
    })
    .await
    .unwrap();
}
fn network(name: &str) -> Command {
    Command::Network(Ok(evaluate(vec![Profile {
        name: name.into(),
        interface_id: "wifi".into(),
        physical_wifi_up: true,
        internet: false,
        ipv4: Some("10.0.0.2".parse().unwrap()),
    }])))
}

#[tokio::test]
async fn actor_persists_preferences_and_cancels_before_reconnecting() {
    let dir = tempfile::tempdir().unwrap();
    let (started, mut starts) = mpsc::unbounded_channel();
    let (mut handle, task) = create(Store::new(dir.path()), TestRunner(started), |_| {});
    let worker = tokio::spawn(task);
    handle.send(network("Caltrain_WiFi 4"));
    handle.send(Command::ToggleConsent);
    handle.send(Command::ToggleAutomatic);
    state(&mut handle.view, |v| v.busy).await;
    let (a, old) = starts.recv().await.unwrap();
    assert!(Store::new(dir.path()).load().unwrap().automatic);
    handle.send(network("Caltrain_WiFi 15"));
    let (b, finish) = tokio::time::timeout(Duration::from_secs(2), starts.recv())
        .await
        .unwrap()
        .unwrap();
    assert_ne!(a.target, b.target);
    assert!(old.is_closed());
    finish.send(Outcome::Connected).unwrap();
    state(&mut handle.view, |v| {
        !v.busy && v.status.starts_with("Connected:")
    })
    .await;
    handle.stop();
    tokio::time::timeout(Duration::from_secs(2), worker)
        .await
        .unwrap()
        .unwrap();
}
#[tokio::test]
async fn failed_settings_save_never_enables_login() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir(dir.path().join("settings.json")).unwrap();
    let (started, mut starts) = mpsc::unbounded_channel();
    let (mut handle, task) = create(Store::new(dir.path()), TestRunner(started), |_| {});
    let worker = tokio::spawn(task);
    handle.send(network("Caltrain_WiFi"));
    handle.send(Command::ToggleConsent);
    state(&mut handle.view, |v| {
        v.status.contains("could not be saved")
    })
    .await;
    assert!(!handle.view.borrow().settings.consent);
    assert!(starts.try_recv().is_err());
    handle.stop();
    worker.await.unwrap();
}
#[tokio::test]
async fn update_pause_waits_until_login_has_stopped_and_shutdown_cancels_work() {
    let dir = tempfile::tempdir().unwrap();
    Store::new(dir.path())
        .save(&Settings {
            consent: true,
            automatic: true,
            ..Settings::default()
        })
        .unwrap();
    let (started, mut starts) = mpsc::unbounded_channel();
    let (handle, task) = create(Store::new(dir.path()), TestRunner(started), |_| {});
    let worker = tokio::spawn(task);
    handle.send(network("Caltrain_WiFi"));
    let (_, completion) = tokio::time::timeout(Duration::from_secs(2), starts.recv())
        .await
        .unwrap()
        .unwrap();
    tokio::time::timeout(Duration::from_secs(2), handle.pause())
        .await
        .unwrap()
        .unwrap();
    assert!(completion.is_closed());
    assert!(!handle.view.borrow().busy);
    handle.send(Command::Resume);
    let (_, completion) = tokio::time::timeout(Duration::from_secs(2), starts.recv())
        .await
        .unwrap()
        .unwrap();
    handle.stop();
    tokio::time::timeout(Duration::from_secs(2), worker)
        .await
        .unwrap()
        .unwrap();
    assert!(completion.is_closed());
}

#[tokio::test]
async fn explicit_preferences_are_idempotent_persisted_and_revocation_cancels() {
    let dir = tempfile::tempdir().unwrap();
    let (started, mut starts) = mpsc::unbounded_channel();
    let (handle, task) = create(Store::new(dir.path()), TestRunner(started), |_| {});
    let worker = tokio::spawn(task);
    handle.send(network("Caltrain_WiFi"));
    handle
        .set_preference(Preference::Consent, true)
        .await
        .unwrap();
    handle
        .set_preference(Preference::Consent, true)
        .await
        .unwrap();
    assert!(Store::new(dir.path()).load().unwrap().consent);
    handle
        .set_preference(Preference::Automatic, true)
        .await
        .unwrap();
    let (_, completion) = starts.recv().await.unwrap();
    handle
        .set_preference(Preference::Consent, false)
        .await
        .unwrap();
    handle.pause().await.unwrap();
    assert!(completion.is_closed());
    let saved = Store::new(dir.path()).load().unwrap();
    assert!(!saved.consent && !saved.automatic);
    assert!(!handle.view.borrow().settings.consent);
    handle.stop();
    worker.await.unwrap();
}

#[tokio::test]
async fn explicit_preference_reports_failed_persistence() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir(dir.path().join("settings.json")).unwrap();
    let (started, _) = mpsc::unbounded_channel();
    let (handle, task) = create(Store::new(dir.path()), TestRunner(started), |_| {});
    let worker = tokio::spawn(task);
    assert!(handle
        .set_preference(Preference::Consent, true)
        .await
        .is_err());
    assert!(!handle.view.borrow().settings.consent);
    handle.stop();
    worker.await.unwrap();
}
