// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

use caltrain_wifi::portal::*;
use serde_json::Value;
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
    time::Duration,
};
use tokio_util::sync::CancellationToken;

fn fixtures() -> Value {
    serde_json::from_str(include_str!("../../shared/fixtures/portal-cases.json")).unwrap()
}
fn page() -> String {
    fixtures()["portal_cases"][0]["html"]
        .as_str()
        .unwrap()
        .to_owned()
}
fn reply(code: u16, body: impl Into<String>) -> Result<Reply, Error> {
    Ok(Reply {
        code,
        body: body.into(),
    })
}
type Request = (String, Option<String>);
struct Server {
    replies: Mutex<VecDeque<Result<Reply, Error>>>,
    requests: Mutex<Vec<Request>>,
}
impl Server {
    fn new(replies: Vec<Result<Reply, Error>>) -> Self {
        Self {
            replies: Mutex::new(replies.into()),
            requests: Mutex::new(vec![]),
        }
    }
    fn posts(&self) -> usize {
        self.requests
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, body)| body.is_some())
            .count()
    }
}
impl Transport for Server {
    async fn exchange(&self, url: &str, body: Option<&str>) -> Result<Reply, Error> {
        self.requests
            .lock()
            .unwrap()
            .push((url.into(), body.map(str::to_owned)));
        self.replies
            .lock()
            .unwrap()
            .pop_front()
            .expect("unexpected request")
    }
}
async fn run(server: &Server) -> Result<LoginResult, Error> {
    login(
        server,
        &CancellationToken::new(),
        || true,
        |_| {},
        Duration::ZERO,
    )
    .await
}
fn fresh(entry: String, auth: String) -> Server {
    Server::new(vec![
        reply(302, ""),
        reply(200, entry),
        reply(200, auth),
        reply(200, "Caltrain"),
        reply(302, ""),
    ])
}

#[tokio::test]
async fn fixture_configurations_submit_only_supported_payloads() {
    for case in fixtures()["portal_cases"].as_array().unwrap() {
        let server = fresh(
            case["html"].as_str().unwrap().into(),
            r#"{"data":{"authenticated":true}}"#.into(),
        );
        let result = run(&server).await;
        if case["payload"].is_null() {
            assert!(
                matches!(result, Err(Error::Configuration)),
                "{}: {result:?}",
                case["name"]
            );
            assert_eq!(server.posts(), 0);
        } else {
            assert_eq!(
                result.unwrap(),
                LoginResult::AcceptedUnverified,
                "{}",
                case["name"]
            );
            let requests = server.requests.lock().unwrap();
            let post = requests.iter().find(|(_, body)| body.is_some()).unwrap();
            assert_eq!(
                serde_json::from_str::<Value>(post.1.as_ref().unwrap()).unwrap(),
                case["payload"]
            );
        }
    }
}

#[tokio::test]
async fn authentication_requires_boolean_true_before_final_page() {
    for case in fixtures()["auth_cases"].as_array().unwrap() {
        let server = fresh(page(), case["body"].as_str().unwrap().into());
        let result = run(&server).await;
        if case["authenticated"] == true {
            assert_eq!(result.unwrap(), LoginResult::AcceptedUnverified);
        } else {
            assert!(matches!(result, Err(Error::Rejected)), "{}", case["name"]);
            assert!(!server
                .requests
                .lock()
                .unwrap()
                .iter()
                .any(|(url, _)| url == LANDING_URL));
        }
    }
}

#[tokio::test]
async fn fresh_login_loads_final_page_before_verifying_and_posts_once() {
    let server = Server::new(vec![
        reply(302, ""),
        reply(200, page()),
        reply(200, r#"{"data":{"authenticated":true}}"#),
        reply(200, "Caltrain"),
        reply(200, "Microsoft Connect Test"),
    ]);
    assert_eq!(run(&server).await.unwrap(), LoginResult::Connected);
    assert_eq!(
        *server.requests.lock().unwrap(),
        vec![
            (PROBE_URL.into(), None),
            (PORTAL_URL.into(), None),
            (AUTH_URL.into(), Some(r#"{"limited":false}"#.into())),
            (LANDING_URL.into(), None),
            (PROBE_URL.into(), None)
        ]
    );
}

#[tokio::test]
async fn only_exact_probe_response_is_online() {
    for (code, body, online) in [
        (200, "Microsoft Connect Test", true),
        (200, "Microsoft Connect Test\n", false),
        (302, "Microsoft Connect Test", false),
    ] {
        let server = Server::new(vec![reply(code, body), reply(500, "")]);
        let result = run(&server).await;
        if online {
            assert_eq!(result.unwrap(), LoginResult::AlreadyOnline);
            assert_eq!(server.requests.lock().unwrap().len(), 1);
        } else {
            assert!(matches!(result, Err(Error::EntryHttp(500))));
        }
    }
}

#[tokio::test]
async fn redirected_entry_rechecks_without_posting_or_following_location() {
    for online in [false, true] {
        let server = Server::new(vec![
            reply(302, ""),
            reply(302, ""),
            reply(
                200,
                if online {
                    "Microsoft Connect Test"
                } else {
                    "portal"
                },
            ),
        ]);
        let result = run(&server).await;
        if online {
            assert_eq!(result.unwrap(), LoginResult::AlreadyOnline);
        } else {
            assert!(matches!(result, Err(Error::Redirected)));
        }
        assert_eq!(server.posts(), 0);
    }
}

#[tokio::test]
async fn revoking_eligibility_at_submit_stage_prevents_post() {
    let allowed = AtomicBool::new(true);
    let server = fresh(page(), "{}".into());
    let result = login(
        &server,
        &CancellationToken::new(),
        || allowed.load(Ordering::SeqCst),
        |stage| {
            if stage == Stage::Submitting {
                allowed.store(false, Ordering::SeqCst);
            }
        },
        Duration::ZERO,
    )
    .await;
    assert!(matches!(result, Err(Error::Cancelled)));
    assert_eq!(server.posts(), 0);
}

#[tokio::test]
async fn cancelled_attempt_sends_nothing() {
    let server = Server::new(vec![]);
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(matches!(
        login(&server, &cancel, || true, |_| {}, Duration::ZERO).await,
        Err(Error::Cancelled)
    ));
    assert!(server.requests.lock().unwrap().is_empty());
}

#[tokio::test(start_paused = true)]
async fn landing_failure_and_delayed_connectivity_do_not_repeat_authentication() {
    let server = Server::new(vec![
        reply(302, ""),
        reply(200, page()),
        reply(200, r#"{"data":{"authenticated":true}}"#),
        Err(Error::Session),
        reply(302, ""),
        reply(200, "Microsoft Connect Test"),
    ]);
    assert_eq!(
        login(
            &server,
            &CancellationToken::new(),
            || true,
            |_| {},
            VERIFICATION_TIMEOUT
        )
        .await
        .unwrap(),
        LoginResult::Connected
    );
    assert_eq!(server.posts(), 1);
}

#[tokio::test(start_paused = true)]
async fn verification_expires_without_resubmitting() {
    let server = Server::new(vec![
        reply(302, ""),
        reply(200, page()),
        reply(200, r#"{"data":{"authenticated":true}}"#),
        reply(200, "Caltrain"),
        reply(302, ""),
        reply(302, ""),
    ]);
    let start = tokio::time::Instant::now();
    assert_eq!(
        login(
            &server,
            &CancellationToken::new(),
            || true,
            |_| {},
            Duration::from_secs(3)
        )
        .await
        .unwrap(),
        LoginResult::AcceptedUnverified
    );
    assert_eq!(start.elapsed(), Duration::from_secs(3));
    assert_eq!(server.posts(), 1);
}

#[tokio::test]
async fn revoked_eligibility_after_loading_portal_prevents_post() {
    struct Revoking {
        server: Server,
        allowed: AtomicBool,
    }
    impl Transport for Revoking {
        async fn exchange(&self, url: &str, body: Option<&str>) -> Result<Reply, Error> {
            let result = self.server.exchange(url, body).await;
            if url == PORTAL_URL {
                self.allowed.store(false, Ordering::SeqCst);
            }
            result
        }
    }
    let transport = Revoking {
        server: fresh(page(), "{}".into()),
        allowed: AtomicBool::new(true),
    };
    assert!(matches!(
        login(
            &transport,
            &CancellationToken::new(),
            || transport.allowed.load(Ordering::SeqCst),
            |_| {},
            Duration::ZERO
        )
        .await,
        Err(Error::Cancelled)
    ));
    assert_eq!(transport.server.posts(), 0);
}

#[tokio::test]
async fn authentication_http_error_never_loads_final_page() {
    let server = Server::new(vec![
        reply(302, ""),
        reply(200, page()),
        reply(503, r#"{"data":{"authenticated":true}}"#),
    ]);
    assert!(matches!(run(&server).await, Err(Error::AuthHttp(503))));
    assert_eq!(server.requests.lock().unwrap().len(), 3);
}

#[tokio::test(start_paused = true)]
async fn cancellation_stops_verification_wait_without_more_requests() {
    let server = fresh(page(), r#"{"data":{"authenticated":true}}"#.into());
    let cancel = CancellationToken::new();
    let attempt = login(&server, &cancel, || true, |_| {}, VERIFICATION_TIMEOUT);
    let trigger = async {
        tokio::time::sleep(Duration::from_millis(100)).await;
        cancel.cancel();
    };
    let (result, ()) = tokio::join!(attempt, trigger);
    assert!(matches!(result, Err(Error::Cancelled)));
    assert_eq!(server.requests.lock().unwrap().len(), 5);
}
