use serde::{de::DeserializeOwned, Deserialize};
use std::{future::Future, time::Duration};
use tokio_util::sync::CancellationToken;

pub const PORTAL_ORIGIN: &str = "https://caltrain.passengerwifi.com";
pub const PORTAL_URL: &str = "https://caltrain.passengerwifi.com/en/authentication";
pub const AUTH_URL: &str = "https://caltrain.passengerwifi.com/api/v1/auth/process";
pub const LANDING_URL: &str = "https://www.caltrain.com/wi-fi";
pub const PROBE_URL: &str = "http://www.msftconnecttest.com/connecttest.txt";
pub const VERIFICATION_TIMEOUT: Duration = Duration::from_secs(45);

#[derive(Debug, PartialEq, Eq)]
pub enum LoginResult {
    AlreadyOnline,
    Connected,
    AcceptedUnverified,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    Checking,
    LoadingPortal,
    Submitting,
    LoadingFinalPage,
    Verifying,
}

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Sign-in cancelled.")]
    Cancelled,
    #[error("Portal requirements changed. Use the normal sign-in page.")]
    Configuration,
    #[error("Portal redirected; no sign-in was submitted.")]
    Redirected,
    #[error("Portal returned HTTP {0}.")]
    EntryHttp(u16),
    #[error("Sign-in returned HTTP {0}.")]
    AuthHttp(u16),
    #[error("The portal did not authenticate this device.")]
    Rejected,
    #[error("HTTP request failed.")]
    Http(#[from] reqwest::Error),
    #[error("Could not bind to the selected Wi-Fi address.")]
    InterfaceBinding(#[source] std::io::Error),
    #[error("Portal response exceeded the size limit.")]
    ResponseTooLarge,
    #[error("Portal session could not be read.")]
    Session,
}

pub struct Reply {
    pub code: u16,
    pub body: String,
}
pub trait Transport: Sync {
    fn exchange(
        &self,
        url: &str,
        body: Option<&str>,
    ) -> impl Future<Output = Result<Reply, Error>> + Send;
}

/// Run one attempt. The caller must establish consent and network eligibility,
/// and cancel the token when either changes. Dropping this future also aborts
/// its in-flight HTTP request. Never reuse an HTTP session between attempts.
pub async fn login(
    transport: &impl Transport,
    cancellation: &CancellationToken,
    allowed: impl Fn() -> bool,
    stage: impl Fn(Stage),
    verification: Duration,
) -> Result<LoginResult, Error> {
    let attempt = async {
        let check = || {
            if allowed() && !cancellation.is_cancelled() {
                Ok(())
            } else {
                Err(Error::Cancelled)
            }
        };
        let publish = |value| {
            check()?;
            stage(value);
            check()
        };
        publish(Stage::Checking)?;
        if online(transport).await {
            check()?;
            return Ok(LoginResult::AlreadyOnline);
        }
        publish(Stage::LoadingPortal)?;
        let entry = transport.exchange(PORTAL_URL, None).await?;
        check()?;
        if (300..400).contains(&entry.code) {
            let connected = online(transport).await;
            check()?;
            return if connected {
                Ok(LoginResult::AlreadyOnline)
            } else {
                Err(Error::Redirected)
            };
        }
        if entry.code != 200 {
            return Err(Error::EntryHttp(entry.code));
        }
        let payload = configuration(&entry.body)?;
        publish(Stage::Submitting)?;
        let auth = transport.exchange(AUTH_URL, Some(&payload)).await?;
        check()?;
        if !(200..300).contains(&auth.code) {
            return Err(Error::AuthHttp(auth.code));
        }
        // Serde structs also accept positional arrays. Require the portal's
        // object shape first, then typed decoding rejects duplicate fields.
        let object_shape = serde_json::from_str::<serde_json::Value>(&auth.body)
            .is_ok_and(|value| value.is_object() && value["data"].is_object());
        let authenticated = if object_shape {
            serde_json::from_str::<Authentication>(&auth.body)
                .ok()
                .map(|value| value.data.authenticated)
        } else {
            None
        };
        if authenticated != Some(true) {
            return Err(Error::Rejected);
        }

        publish(Stage::LoadingFinalPage)?;
        // This navigation completed sign-in in live testing. A failure here
        // does not justify repeating the already accepted authentication POST.
        let _ = transport.exchange(LANDING_URL, None).await;
        publish(Stage::Verifying)?;
        let deadline = tokio::time::Instant::now() + verification;
        loop {
            check()?;
            let connected = online(transport).await;
            check()?;
            if connected {
                return Ok(LoginResult::Connected);
            }
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                return Ok(LoginResult::AcceptedUnverified);
            }
            tokio::time::sleep(remaining.min(Duration::from_millis(1500))).await;
            if tokio::time::Instant::now() >= deadline {
                return Ok(LoginResult::AcceptedUnverified);
            }
        }
    };
    tokio::select! {
        biased;
        _ = cancellation.cancelled() => Err(Error::Cancelled),
        result = attempt => result,
    }
}

async fn online(transport: &impl Transport) -> bool {
    matches!(transport.exchange(PROBE_URL, None).await,
        Ok(Reply { code: 200, body }) if body == "Microsoft Connect Test")
}

// Typed decoding rejects missing, mistyped, and duplicate decision fields.
// Unknown fields remain ignorable; portal scripts often contain unrelated data.
#[derive(Deserialize)]
struct Features {
    terms: bool,
    sms_authentication: bool,
    email_credentials: bool,
    name_credentials: bool,
    marketing: bool,
    remember: bool,
    limited_bandwidth: bool,
    success_url: String,
}
#[derive(Deserialize)]
struct Content {
    auth_frontend: String,
    auth_bandwidth: serde_json::Value,
}
#[derive(Deserialize)]
struct Authentication {
    data: AuthData,
}
#[derive(Deserialize)]
struct AuthData {
    authenticated: bool,
}

fn configuration(html: &str) -> Result<String, Error> {
    let features: Features = assignment(html, r"window\.features\s*=\s*")?;
    let content: Content = assignment(html, r"(?:var\s+ce_data|window\.ce_data)\s*=\s*")?;
    if content.auth_frontend != "api"
        || !features.terms
        || features.sms_authentication
        || features.email_credentials
        || features.name_credentials
        || features.marketing
        || features.remember
        || features.success_url != LANDING_URL
    {
        return Err(Error::Configuration);
    }
    let limited = features.limited_bandwidth && content.auth_bandwidth == "limited";
    Ok(format!(r#"{{"limited":{limited}}}"#))
}

fn assignment<T: DeserializeOwned>(html: &str, pattern: &str) -> Result<T, Error> {
    let found = regex::Regex::new(pattern)
        .expect("constant assignment pattern")
        .find(html)
        .ok_or(Error::Configuration)?;
    // A streaming JSON parser consumes exactly one balanced object, including
    // quoted braces/escapes, and leaves the surrounding JavaScript untouched.
    let remaining = &html[found.end()..];
    if !remaining.starts_with('{') {
        return Err(Error::Configuration);
    }
    serde_json::Deserializer::from_str(remaining)
        .into_iter::<T>()
        .next()
        .ok_or(Error::Configuration)?
        .map_err(|_| Error::Configuration)
}
