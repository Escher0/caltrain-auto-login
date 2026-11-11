// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

use caltrain_wifi::{
    diagnostics::{describe, guidance, kind, Diagnostic, FailureKind, ObservedTransport},
    http_session::HttpSession,
    portal::{self, Error, Reply, Stage, Transport},
};
use std::{
    io,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio_util::sync::CancellationToken;

struct FailedDns(io::ErrorKind);
impl reqwest::dns::Resolve for FailedDns {
    fn resolve(&self, _: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let error = self.0;
        Box::pin(async move { Err(io::Error::from(error).into()) })
    }
}

#[tokio::test]
async fn refused_loopback_connection_is_classified_without_blame() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    drop(listener);
    let client = reqwest::Client::builder()
        .no_proxy()
        // Windows can take longer than two seconds to report connection refusal.
        .timeout(Duration::from_secs(10))
        .retry(reqwest::retry::never())
        .build()
        .unwrap();
    let error = Error::Http(
        client
            .get(format!("http://{address}/"))
            .send()
            .await
            .unwrap_err(),
    );
    assert_eq!(kind(&error), FailureKind::Connection);
    assert!(guidance(&error).contains("Could not establish a connection"));
}

#[tokio::test]
async fn stalled_loopback_response_is_classified_as_timeout() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(Duration::from_millis(100))
        .build()
        .unwrap();
    let request = client.get(format!("http://{address}/")).send();
    let (result, accepted) = tokio::join!(
        request,
        tokio::time::timeout(Duration::from_secs(2), listener.accept())
    );
    // Keep the accepted socket open, but send no response.
    let (_socket, _) = accepted.unwrap().unwrap();
    let error = Error::Http(result.unwrap_err());
    assert_eq!(kind(&error), FailureKind::Timeout);
    assert!(guidance(&error).contains("timed out"));
}

#[tokio::test]
async fn reqwest_dns_errors_are_typed_and_urls_are_redacted() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    for (cause, expected) in [
        (io::ErrorKind::NotFound, FailureKind::Dns),
        (io::ErrorKind::PermissionDenied, FailureKind::AccessDenied),
    ] {
        let client = reqwest::Client::builder()
            .no_proxy()
            .dns_resolver(Arc::new(FailedDns(cause)))
            .build()
            .unwrap();
        let error = Error::Http(
            client
                .get("http://portal.invalid/private?secret=value")
                .send()
                .await
                .unwrap_err(),
        );
        assert_eq!(kind(&error), expected);
        let detail = describe(&error);
        assert!(detail.contains("[URL omitted]"));
        assert!(!detail.contains("private"));
        assert!(!detail.contains("secret"));
    }
}

#[test]
fn typed_io_failures_have_distinct_guidance_without_claiming_a_vpn_cause() {
    for (error, expected) in [
        (io::ErrorKind::PermissionDenied, FailureKind::AccessDenied),
        (
            io::ErrorKind::AddrNotAvailable,
            FailureKind::AddressUnavailable,
        ),
        (io::ErrorKind::TimedOut, FailureKind::Timeout),
        (io::ErrorKind::ConnectionRefused, FailureKind::Connection),
    ] {
        let error = Error::InterfaceBinding(io::Error::from(error));
        assert_eq!(kind(&error), expected);
        assert_ne!(guidance(&error), "HTTP request failed.");
    }
    let message = guidance(&Error::InterfaceBinding(io::Error::from(
        io::ErrorKind::PermissionDenied,
    )));
    assert!(message.contains("may"));
    assert!(!message.contains("AdGuard"));
    assert_eq!(
        guidance(&Error::Configuration),
        Error::Configuration.to_string()
    );
}

#[test]
fn diagnostic_details_hide_urls_and_bound_unicode_text() {
    let error = io::Error::other(format!(
        "failed HTTPS://user:password@example.com/path?secret=token\n{}",
        "é".repeat(1000)
    ));
    let detail = describe(&error);
    assert!(detail.contains("[URL omitted]"));
    for secret in ["password", "secret", "token", "\n"] {
        assert!(!detail.contains(secret));
    }
    assert!(detail.chars().count() <= 700);
}

#[tokio::test]
async fn preflight_rejects_an_unavailable_source_address_before_any_request() {
    assert!(HttpSession::on_interface("127.0.0.1".parse().unwrap()).is_ok());
    let error = match HttpSession::on_interface("192.0.2.123".parse().unwrap()) {
        Ok(_) => panic!("nonlocal address unexpectedly bound"),
        Err(error) => error,
    };
    assert_eq!(kind(&error), FailureKind::AddressUnavailable);
}

struct Portal;
impl Transport for Portal {
    async fn exchange(&self, url: &str, _body: Option<&str>) -> Result<Reply, Error> {
        match url {
            portal::PORTAL_URL => {
                let fixtures: serde_json::Value =
                    serde_json::from_str(include_str!("../../shared/fixtures/portal-cases.json"))
                        .unwrap();
                Ok(Reply {
                    code: 200,
                    body: fixtures["portal_cases"][0]["html"].as_str().unwrap().into(),
                })
            }
            portal::AUTH_URL => Ok(Reply {
                code: 200,
                body: r#"{"data":{"authenticated":true}}"#.into(),
            }),
            _ => Err(Error::Session),
        }
    }
}

#[tokio::test]
async fn swallowed_probe_and_final_page_errors_keep_their_host_and_stage() {
    let records = Arc::new(Mutex::new(Vec::<Diagnostic>::new()));
    let output = records.clone();
    let transport = ObservedTransport::new(
        Portal,
        Arc::new(move |record| output.lock().unwrap().push(record)),
    );
    let result = portal::login(
        &transport,
        &CancellationToken::new(),
        || true,
        |stage| transport.set_stage(stage),
        Duration::ZERO,
    )
    .await
    .unwrap();
    assert_eq!(result, portal::LoginResult::AcceptedUnverified);
    // Repeated failures at the same verification stage are recorded only once.
    let _ = transport.exchange(portal::PROBE_URL, None).await;
    let records = records.lock().unwrap();
    assert_eq!(records.len(), 3);
    assert_eq!(records[0].step, "Checking");
    assert_eq!(records[1].step, "LoadingFinalPage");
    assert_eq!(records[1].host.as_deref(), Some("www.caltrain.com"));
    assert_eq!(records[2].step, "Verifying");
    assert_eq!(records[2].host.as_deref(), Some("www.msftconnecttest.com"));
}

#[tokio::test]
async fn cancelled_runner_does_not_attempt_preflight() {
    use caltrain_wifi::{
        controller::{Attempt, Outcome},
        network::Target,
        runtime::{PortalRunner, Runner},
    };
    let cancel = CancellationToken::new();
    cancel.cancel();
    let runner = PortalRunner::new(Arc::new(|_| {
        panic!("cancelled attempt emitted a diagnostic")
    }));
    let result = runner
        .run(
            Attempt {
                id: 1,
                manual: true,
                target: Target {
                    identity: "test".into(),
                    address: "192.0.2.123".parse().unwrap(),
                },
            },
            cancel,
            Arc::new(|_: Stage| {}),
        )
        .await;
    assert!(matches!(result, Outcome::Cancelled));
}
