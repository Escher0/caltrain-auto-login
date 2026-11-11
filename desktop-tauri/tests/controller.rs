use caltrain_wifi::{
    controller::*,
    network::{evaluate, Profile, Snapshot},
    portal::Stage,
    settings::Settings,
};
use std::time::{Duration, Instant};

fn wifi(name: &str) -> Snapshot {
    evaluate(vec![Profile {
        name: name.into(),
        interface_id: "wifi".into(),
        physical_wifi_up: true,
        internet: false,
        ipv4: Some("10.0.0.2".parse().unwrap()),
    }])
}
fn enabled() -> Settings {
    Settings {
        consent: true,
        automatic: true,
        ..Settings::default()
    }
}
fn started(effects: Vec<Effect>) -> Attempt {
    match effects.as_slice() {
        [Effect::Start(a)] => a.clone(),
        _ => panic!("expected one start, got {effects:?}"),
    }
}

#[test]
fn consent_and_automatic_are_required_but_manual_only_needs_consent() {
    let now = Instant::now();
    let mut c = Controller::new(Settings::default());
    assert!(c
        .handle(Event::Network(Ok(wifi("Caltrain_WiFi 15"))), now)
        .is_empty());
    assert!(c.handle(Event::Manual, now).is_empty());
    assert!(c
        .handle(
            Event::Settings(Settings {
                consent: true,
                ..Settings::default()
            }),
            now
        )
        .is_empty());
    assert!(started(c.handle(Event::Manual, now)).manual);
}
#[test]
fn event_storms_do_not_overlap_or_repeat_a_completed_login() {
    let now = Instant::now();
    let mut c = Controller::new(enabled());
    let a = started(c.handle(Event::Network(Ok(wifi("Caltrain_WiFi 15"))), now));
    for _ in 0..20 {
        assert!(c
            .handle(Event::Network(Ok(wifi("Caltrain_WiFi 15"))), now)
            .is_empty());
    }
    assert!(c.handle(Event::Manual, now).is_empty());
    assert!(c
        .handle(Event::Finished(a.id, Outcome::Connected), now)
        .is_empty());
    assert!(c
        .handle(Event::Network(Ok(wifi("Caltrain_WiFi 15"))), now)
        .is_empty());
    assert!(c
        .handle(Event::Tick, now + Duration::from_secs(299))
        .is_empty());
    started(c.handle(Event::Tick, now + Duration::from_secs(300)));
}
#[test]
fn reconnect_cancels_then_waits_for_old_attempt_before_starting_new_one() {
    let now = Instant::now();
    let mut c = Controller::new(enabled());
    let a = started(c.handle(Event::Network(Ok(wifi("Caltrain_WiFi 4"))), now));
    assert_eq!(
        c.handle(Event::Network(Ok(wifi("Caltrain_WiFi 15"))), now),
        vec![Effect::Cancel(a.id)]
    );
    assert!(c.view().busy);
    let b = started(c.handle(Event::Finished(a.id, Outcome::Connected), now));
    assert_ne!(a.id, b.id);
    assert_ne!(a.target, b.target);
    let status = c.view().status;
    assert!(c
        .handle(
            Event::Finished(a.id, Outcome::Failed("stale error".into())),
            now
        )
        .is_empty());
    c.handle(Event::Stage(a.id, Stage::Verifying), now);
    assert_eq!(c.view().status, status);
    assert!(c.view().busy);
}
#[test]
fn consent_revocation_cancels_even_manual_login_and_cannot_restart() {
    let now = Instant::now();
    let mut c = Controller::new(Settings {
        consent: true,
        ..Settings::default()
    });
    c.handle(Event::Network(Ok(wifi("Caltrain_WiFi"))), now);
    let a = started(c.handle(Event::Manual, now));
    assert_eq!(
        c.handle(Event::Settings(Settings::default()), now),
        vec![Effect::Cancel(a.id)]
    );
    assert!(c
        .handle(Event::Finished(a.id, Outcome::Cancelled), now)
        .is_empty());
    assert!(!c.view().can_connect);
}
#[test]
fn automatic_off_cancels_automatic_attempt_but_manual_still_works() {
    let now = Instant::now();
    let mut c = Controller::new(enabled());
    let a = started(c.handle(Event::Network(Ok(wifi("Caltrain_WiFi"))), now));
    let manual_settings = Settings {
        consent: true,
        ..Settings::default()
    };
    assert_eq!(
        c.handle(Event::Settings(manual_settings.clone()), now),
        vec![Effect::Cancel(a.id)]
    );
    c.handle(Event::Finished(a.id, Outcome::Cancelled), now);
    let b = started(c.handle(Event::Manual, now));
    assert!(c.handle(Event::Settings(manual_settings), now).is_empty());
    assert!(b.manual);
}
#[test]
fn failed_and_unverified_attempts_back_off_and_manual_bypasses_delay() {
    let start = Instant::now();
    let mut now = start;
    let mut c = Controller::new(enabled());
    let mut a = started(c.handle(Event::Network(Ok(wifi("Caltrain_WiFi"))), now));
    for delay in [60, 120, 240, 300, 300] {
        assert!(c
            .handle(Event::Finished(a.id, Outcome::AcceptedUnverified), now)
            .is_empty());
        assert!(c
            .handle(Event::Tick, now + Duration::from_secs(delay - 1))
            .is_empty());
        now += Duration::from_secs(delay);
        a = started(c.handle(Event::Tick, now));
    }
    c.handle(
        Event::Finished(a.id, Outcome::Failed("offline".into())),
        now,
    );
    assert!(started(c.handle(Event::Manual, now)).manual);
}
#[test]
fn lost_network_or_monitor_error_cancels_and_blocks_sign_in() {
    for state in [Ok(wifi("Office")), Err("monitor unavailable".into())] {
        let now = Instant::now();
        let mut c = Controller::new(enabled());
        let a = started(c.handle(Event::Network(Ok(wifi("Caltrain_WiFi"))), now));
        assert_eq!(
            c.handle(Event::Network(state), now),
            vec![Effect::Cancel(a.id)]
        );
        assert!(c
            .handle(Event::Finished(a.id, Outcome::Cancelled), now)
            .is_empty());
        assert!(c.handle(Event::Manual, now).is_empty());
        assert!(!c.view().can_connect);
    }
}
#[test]
fn pause_for_update_and_shutdown_cancel_without_restarting() {
    for event in [Event::Pause(true), Event::Shutdown] {
        let now = Instant::now();
        let mut c = Controller::new(enabled());
        let a = started(c.handle(Event::Network(Ok(wifi("Caltrain_WiFi"))), now));
        assert_eq!(c.handle(event, now), vec![Effect::Cancel(a.id)]);
        assert!(c
            .handle(Event::Finished(a.id, Outcome::Cancelled), now)
            .is_empty());
        assert!(c.handle(Event::Manual, now).is_empty());
    }
}
