use crate::{
    controller::{Attempt, Controller, Effect, Event, Outcome, View},
    diagnostics::{self, Diagnostic, ObservedTransport, Observer},
    http_session::HttpSession,
    network::Snapshot,
    portal::{self, Stage},
    settings::Store,
};
use std::{future::Future, sync::Arc, time::Instant};
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

pub type Progress = Arc<dyn Fn(Stage) + Send + Sync>;
pub trait Runner: Send + Sync + 'static {
    fn run(
        &self,
        attempt: Attempt,
        cancel: CancellationToken,
        progress: Progress,
    ) -> impl Future<Output = Outcome> + Send;
}
pub struct PortalRunner {
    observer: Observer,
}
impl PortalRunner {
    pub fn new(observer: Observer) -> Self {
        Self { observer }
    }
}
impl Runner for PortalRunner {
    async fn run(
        &self,
        attempt: Attempt,
        cancel: CancellationToken,
        progress: Progress,
    ) -> Outcome {
        let result = async {
            if cancel.is_cancelled() {
                return Err(portal::Error::Cancelled);
            }
            let session =
                HttpSession::on_interface(attempt.target.address).inspect_err(|error| {
                    (self.observer)(Diagnostic::preflight(error));
                })?;
            let session = ObservedTransport::new(session, self.observer.clone());
            portal::login(
                &session,
                &cancel,
                || !cancel.is_cancelled(),
                |stage| {
                    session.set_stage(stage);
                    progress(stage);
                },
                portal::VERIFICATION_TIMEOUT,
            )
            .await
        }
        .await;
        match result {
            Ok(portal::LoginResult::Connected) => Outcome::Connected,
            Ok(portal::LoginResult::AlreadyOnline) => Outcome::AlreadyOnline,
            Ok(portal::LoginResult::AcceptedUnverified) => Outcome::AcceptedUnverified,
            Err(portal::Error::Cancelled) => Outcome::Cancelled,
            Err(error) => Outcome::Failed(diagnostics::guidance(&error)),
        }
    }
}
#[derive(Clone, Copy, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Preference {
    Consent,
    Automatic,
    CheckUpdates,
}
pub enum Command {
    SetPreference(Preference, bool, oneshot::Sender<Result<(), String>>),
    Network(Result<Snapshot, String>),
    ToggleConsent,
    ToggleAutomatic,
    ToggleUpdateChecks,
    Connect,
    Notice(String),
    Pause(oneshot::Sender<()>),
    Resume,
}
enum Message {
    Command(Command),
    Stage(u64, Stage),
    Finished(u64, Outcome),
    Tick,
}
struct Job {
    id: u64,
    cancel: CancellationToken,
    task: tokio::task::JoinHandle<Outcome>,
}
#[derive(Clone)]
pub struct Handle {
    sender: mpsc::UnboundedSender<Message>,
    stop: CancellationToken,
    pub view: watch::Receiver<View>,
}
impl Handle {
    pub fn send(&self, command: Command) {
        let _ = self.sender.send(Message::Command(command));
    }
    pub async fn set_preference(
        &self,
        preference: Preference,
        enabled: bool,
    ) -> Result<(), String> {
        let (send, receive) = oneshot::channel();
        self.send(Command::SetPreference(preference, enabled, send));
        receive
            .await
            .map_err(|_| "Application is shutting down".to_string())?
    }
    pub fn stop(&self) {
        self.stop.cancel();
    }
    pub async fn pause(&self) -> Result<(), &'static str> {
        let (send, receive) = oneshot::channel();
        self.send(Command::Pause(send));
        receive.await.map_err(|_| "Application is shutting down")
    }
}

pub fn create<R: Runner>(
    store: Store,
    runner: R,
    publish: impl Fn(&View) + Send + 'static,
) -> (Handle, impl Future<Output = ()> + Send) {
    let loaded = store.load();
    let mut controller = Controller::new(loaded.as_ref().cloned().unwrap_or_default());
    if loaded.is_err() {
        controller.notice("Settings unavailable; sign-in disabled");
    }
    let (sender, mut receiver) = mpsc::unbounded_channel();
    let (views, view) = watch::channel(controller.view());
    let stop = CancellationToken::new();
    let handle = Handle {
        sender: sender.clone(),
        stop: stop.clone(),
        view,
    };
    let task = async move {
        let runner = Arc::new(runner);
        let mut job: Option<Job> = None;
        let mut paused = vec![];
        publish(&controller.view());
        loop {
            let wake = controller.wake_at();
            let message = tokio::select! {
                biased;
                _=stop.cancelled()=>break,
                message=receiver.recv()=>match message {Some(message)=>message,None=>break},
                message=async {
                    if let Some(job)=job.as_mut() {
                        let outcome=(&mut job.task).await.unwrap_or_else(|_|Outcome::Failed("Sign-in worker stopped unexpectedly".into()));
                        Message::Finished(job.id,outcome)
                    } else {std::future::pending().await}
                }=>message,
                _=async {if let Some(wake)=wake {tokio::time::sleep_until(wake.into()).await;} else {std::future::pending::<()>().await;}}=>Message::Tick,
            };
            let mut failed_save = false;
            let mut preference_reply = None;
            let event = match message {
                Message::Stage(id, stage) => Event::Stage(id, stage),
                Message::Finished(id, outcome) => {
                    job.take();
                    Event::Finished(id, outcome)
                }
                Message::Tick => Event::Tick,
                Message::Command(command) => match command {
                    Command::Network(result) => Event::Network(result),
                    Command::Connect => Event::Manual,
                    Command::Pause(reply) => {
                        paused.push(reply);
                        Event::Pause(true)
                    }
                    Command::Resume => Event::Pause(false),
                    Command::Notice(message) => {
                        controller.notice(message);
                        Event::Tick
                    }
                    command => {
                        let mut settings = controller.view().settings;
                        match command {
                            Command::SetPreference(preference, enabled, reply) => {
                                match preference {
                                    Preference::Consent => settings.consent = enabled,
                                    Preference::Automatic => settings.automatic = enabled,
                                    Preference::CheckUpdates => settings.check_updates = enabled,
                                }
                                preference_reply = Some(reply);
                            }
                            Command::ToggleConsent => {
                                settings.consent = !settings.consent;
                            }
                            Command::ToggleAutomatic => {
                                settings.automatic = !settings.automatic;
                            }
                            Command::ToggleUpdateChecks => {
                                settings.check_updates = !settings.check_updates;
                            }
                            _ => unreachable!(),
                        }
                        settings = settings.normalized();
                        if store.save(&settings).is_err() {
                            failed_save = true;
                            settings.consent = false;
                            settings.automatic = false;
                        }
                        Event::Settings(settings)
                    }
                },
            };
            let effects = controller.handle(event, Instant::now());
            if failed_save {
                controller.notice("Settings could not be saved; sign-in paused for this session");
            }
            for effect in effects {
                match effect {
                    Effect::Cancel(id) => {
                        if let Some(job) = &job {
                            if job.id == id {
                                job.cancel.cancel();
                            }
                        }
                    }
                    Effect::Start(attempt) => {
                        let id = attempt.id;
                        let cancel = stop.child_token();
                        let task_cancel = cancel.clone();
                        let progress_sender = sender.clone();
                        let progress: Progress = Arc::new(move |stage| {
                            let _ = progress_sender.send(Message::Stage(id, stage));
                        });
                        let runner = runner.clone();
                        let task = tokio::spawn(async move {
                            runner.run(attempt, task_cancel, progress).await
                        });
                        job = Some(Job { id, cancel, task });
                    }
                }
            }
            let view = controller.view();
            if *views.borrow() != view {
                publish(&view);
                views.send_replace(view.clone());
            }
            if let Some(reply) = preference_reply {
                let result = if failed_save {
                    Err("Settings could not be saved; sign-in paused for this session".into())
                } else {
                    Ok(())
                };
                let _ = reply.send(result);
            }
            if !view.busy {
                for reply in paused.drain(..) {
                    let _ = reply.send(());
                }
            }
        }
        if let Some(mut job) = job {
            job.cancel.cancel();
            if tokio::time::timeout(std::time::Duration::from_secs(3), &mut job.task)
                .await
                .is_err()
            {
                job.task.abort();
            }
        }
    };
    (handle, task)
}
