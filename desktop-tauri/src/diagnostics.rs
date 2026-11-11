// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

//! Local, bounded network diagnostics. A failure category is not proof of a VPN.
use crate::portal::{Error, Reply, Stage, Transport};
use serde::Serialize;
use std::{
    error::Error as StdError,
    io,
    sync::{Arc, LazyLock, Mutex},
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum FailureKind {
    Dns,
    AccessDenied,
    AddressUnavailable,
    Timeout,
    Connection,
    Other,
}

pub fn kind(error: &Error) -> FailureKind {
    let mut current: Option<&(dyn StdError + 'static)> = Some(error);
    for _ in 0..8 {
        let Some(cause) = current else { break };
        if let Some(error) = cause.downcast_ref::<io::Error>() {
            match error.kind() {
                io::ErrorKind::PermissionDenied => return FailureKind::AccessDenied,
                io::ErrorKind::AddrNotAvailable => return FailureKind::AddressUnavailable,
                _ => {}
            }
        }
        current = cause.source();
    }
    if let Error::Http(error) = error {
        if error.is_dns() {
            return FailureKind::Dns;
        }
        if error.is_timeout() {
            return FailureKind::Timeout;
        }
        if error.is_connect() {
            return FailureKind::Connection;
        }
    }
    if let Error::InterfaceBinding(error) = error {
        match error.kind() {
            io::ErrorKind::TimedOut => return FailureKind::Timeout,
            io::ErrorKind::ConnectionRefused
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::NotConnected => return FailureKind::Connection,
            _ => {}
        }
    }
    FailureKind::Other
}

pub fn guidance(error: &Error) -> String {
    match kind(error) {
        FailureKind::Dns => "DNS lookup failed. Check Wi-Fi and any VPN or filtering app's DNS settings. See VPN & connection help.".into(),
        FailureKind::AccessDenied => "Network access was denied. A VPN, firewall or filtering app may be blocking it. See VPN & connection help.".into(),
        FailureKind::AddressUnavailable => "The selected Wi-Fi address is no longer available. Reconnect to Caltrain Wi-Fi or refresh network status.".into(),
        FailureKind::Timeout => "The network request timed out. Check Wi-Fi and VPN & connection help.".into(),
        FailureKind::Connection => "Could not establish a connection. Check Wi-Fi and VPN & connection help; the activity log has details.".into(),
        FailureKind::Other => error.to_string(),
    }
}

pub fn describe(error: &(dyn StdError + 'static)) -> String {
    static URL: LazyLock<regex::Regex> =
        LazyLock::new(|| regex::Regex::new(r"(?i)https?://[^\s]+").unwrap());
    let mut details = Vec::new();
    let mut current = Some(error);
    for _ in 0..8 {
        let Some(cause) = current else { break };
        details.push(cause.to_string());
        current = cause.source();
    }
    URL.replace_all(&details.join(" <- "), "[URL omitted]")
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .take(700)
        .collect()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Diagnostic {
    pub step: String,
    pub host: Option<String>,
    pub kind: FailureKind,
    pub detail: String,
}
impl Diagnostic {
    pub fn preflight(error: &Error) -> Self {
        Self {
            step: "BindingWifiAddress".into(),
            host: None,
            kind: kind(error),
            detail: describe(error),
        }
    }
}
pub type Observer = Arc<dyn Fn(Diagnostic) + Send + Sync>;

/// Observes even errors the protocol intentionally ignores, without changing retries.
pub struct ObservedTransport<T> {
    inner: T,
    stage: Mutex<Stage>,
    observer: Observer,
    last: Mutex<Option<Diagnostic>>,
}
impl<T> ObservedTransport<T> {
    pub fn new(inner: T, observer: Observer) -> Self {
        Self {
            inner,
            observer,
            stage: Mutex::new(Stage::Checking),
            last: Mutex::new(None),
        }
    }
    pub fn set_stage(&self, stage: Stage) {
        *self.stage.lock().unwrap() = stage;
    }
}
impl<T: Transport> Transport for ObservedTransport<T> {
    async fn exchange(&self, url: &str, body: Option<&str>) -> Result<Reply, Error> {
        let result = self.inner.exchange(url, body).await;
        if let Err(error) = &result {
            let record = Diagnostic {
                step: format!("{:?}", *self.stage.lock().unwrap()),
                host: reqwest::Url::parse(url)
                    .ok()
                    .and_then(|url| url.host_str().map(str::to_owned)),
                kind: kind(error),
                detail: describe(error),
            };
            let changed = {
                let mut last = self.last.lock().unwrap();
                if last.as_ref() == Some(&record) {
                    false
                } else {
                    *last = Some(record.clone());
                    true
                }
            };
            if changed {
                (self.observer)(record);
            }
        } else {
            *self.last.lock().unwrap() = None;
        }
        result
    }
}
