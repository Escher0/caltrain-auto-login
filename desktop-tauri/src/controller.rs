// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

use crate::{
    network::{Snapshot, Target},
    portal::Stage,
    settings::Settings,
};
use std::time::{Duration, Instant};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Attempt {
    pub id: u64,
    pub target: Target,
    pub manual: bool,
}
#[derive(Debug, PartialEq, Eq)]
pub enum Effect {
    Start(Attempt),
    Cancel(u64),
}
#[derive(Clone, Debug)]
pub enum Outcome {
    AlreadyOnline,
    Connected,
    AcceptedUnverified,
    Failed(String),
    Cancelled,
}
pub enum Event {
    Network(Result<Snapshot, String>),
    Settings(Settings),
    Manual,
    Tick,
    Stage(u64, Stage),
    Finished(u64, Outcome),
    Pause(bool),
    Shutdown,
}
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct View {
    pub settings: Settings,
    pub busy: bool,
    pub can_connect: bool,
    pub status: String,
}
struct Active {
    attempt: Attempt,
    cancelled: bool,
}
pub struct Controller {
    settings: Settings,
    network: Option<Snapshot>,
    active: Option<Active>,
    next_id: u64,
    retry_at: Option<Instant>,
    failures: u32,
    paused: bool,
    stopped: bool,
    status: String,
}
impl Controller {
    pub fn new(settings: Settings) -> Self {
        Self {
            settings: settings.normalized(),
            network: None,
            active: None,
            next_id: 0,
            retry_at: None,
            failures: 0,
            paused: false,
            stopped: false,
            status: "Checking Wi-Fi...".into(),
        }
    }
    fn target(&self) -> Option<Target> {
        self.network.as_ref().and_then(Snapshot::target)
    }
    fn cancel(&mut self, effects: &mut Vec<Effect>) {
        if let Some(active) = self.active.as_mut() {
            if !active.cancelled {
                active.cancelled = true;
                effects.push(Effect::Cancel(active.attempt.id));
            }
        }
    }
    pub fn handle(&mut self, event: Event, now: Instant) -> Vec<Effect> {
        let mut effects = vec![];
        let manual = matches!(event, Event::Manual);
        match event {
            Event::Network(result) => {
                let previous = self.target();
                let was_online = self.network.as_ref().is_some_and(Snapshot::internet);
                let notice = match result {
                    Ok(snapshot) => {
                        let notice = snapshot.status.clone();
                        self.network = Some(snapshot);
                        notice
                    }
                    Err(_) => {
                        self.network = None;
                        "Wi-Fi monitor unavailable; sign-in paused".into()
                    }
                };
                let target = self.target();
                if target != previous || target.is_none() {
                    self.cancel(&mut effects);
                    self.retry_at = None;
                    self.failures = 0;
                    self.status = notice;
                } else if was_online
                    && !self.network.as_ref().is_some_and(Snapshot::internet)
                    && self.active.is_none()
                {
                    self.retry_at = None;
                    self.failures = 0;
                    self.status = notice;
                }
            }
            Event::Settings(settings) => {
                let previous = self.settings.clone();
                self.settings = settings.normalized();
                if !self.settings.consent
                    || (!self.settings.automatic
                        && self.active.as_ref().is_some_and(|a| !a.attempt.manual))
                {
                    self.cancel(&mut effects);
                }
                if !self.settings.consent {
                    self.status = "Authorize accepting the Wi-Fi terms to enable sign-in".into();
                } else if !self.settings.automatic && previous.automatic {
                    self.status = "Automatic sign-in paused".into();
                } else if self.settings.automatic && !previous.automatic {
                    self.retry_at = None;
                    self.failures = 0;
                } else if !previous.consent {
                    self.status = "Terms authorization saved".into();
                }
            }
            Event::Stage(id, stage) => {
                if self
                    .active
                    .as_ref()
                    .is_some_and(|a| a.attempt.id == id && !a.cancelled)
                {
                    self.status = match stage {
                        Stage::Checking => "Checking Caltrain Internet access...",
                        Stage::LoadingPortal => "Loading the Caltrain sign-in page...",
                        Stage::Submitting => "Accepting terms and signing in...",
                        Stage::LoadingFinalPage => "Sign-in accepted; loading the final page...",
                        Stage::Verifying => "Checking Internet access...",
                    }
                    .into();
                }
            }
            Event::Finished(id, outcome) => {
                if !self.active.as_ref().is_some_and(|a| a.attempt.id == id) {
                    return effects;
                }
                let active = self.active.take().unwrap();
                if !active.cancelled {
                    let connected = matches!(outcome, Outcome::AlreadyOnline | Outcome::Connected);
                    self.status = match outcome {
                        Outcome::AlreadyOnline => {
                            "Connected: Internet access already available".into()
                        }
                        Outcome::Connected => "Connected: Internet access verified".into(),
                        Outcome::AcceptedUnverified => {
                            "Sign-in accepted; Internet access not yet verified".into()
                        }
                        Outcome::Failed(message) => format!("Sign-in failed: {message}"),
                        Outcome::Cancelled => "Sign-in cancelled".into(),
                    };
                    let delay = if connected {
                        self.failures = 0;
                        300
                    } else {
                        self.failures = self.failures.saturating_add(1);
                        (60u64 * 2u64.pow(self.failures.saturating_sub(1).min(3))).min(300)
                    };
                    self.retry_at = Some(now + Duration::from_secs(delay));
                }
            }
            Event::Pause(paused) => {
                self.paused = paused;
                if paused {
                    self.cancel(&mut effects);
                    self.status = "Preparing update...".into();
                } else {
                    self.status = self
                        .network
                        .as_ref()
                        .map(|n| n.status.clone())
                        .unwrap_or_else(|| "Checking Wi-Fi...".into());
                }
            }
            Event::Shutdown => {
                self.stopped = true;
                self.cancel(&mut effects);
            }
            Event::Manual | Event::Tick => {}
        }
        if !self.stopped
            && !self.paused
            && self.active.is_none()
            && self.settings.consent
            && (manual || self.settings.automatic)
            && (manual || self.retry_at.is_none_or(|deadline| now >= deadline))
        {
            if let Some(target) = self.target() {
                self.next_id += 1;
                let attempt = Attempt {
                    id: self.next_id,
                    target,
                    manual,
                };
                self.active = Some(Active {
                    attempt: attempt.clone(),
                    cancelled: false,
                });
                self.retry_at = None;
                self.status = "Checking Caltrain Internet access...".into();
                effects.push(Effect::Start(attempt));
            }
        }
        effects
    }
    pub fn notice(&mut self, message: impl Into<String>) {
        self.status = message.into();
    }
    pub fn view(&self) -> View {
        View {
            settings: self.settings.clone(),
            busy: self.active.is_some(),
            can_connect: !self.stopped
                && !self.paused
                && self.active.is_none()
                && self.settings.consent
                && self.target().is_some(),
            status: self.status.clone(),
        }
    }
    pub fn wake_at(&self) -> Option<Instant> {
        if self.settings.automatic
            && self.settings.consent
            && !self.paused
            && !self.stopped
            && self.active.is_none()
            && self.target().is_some()
        {
            self.retry_at
        } else {
            None
        }
    }
}
