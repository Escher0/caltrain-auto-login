// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

use crate::portal::{Error, Reply, Transport, PORTAL_ORIGIN, PORTAL_URL};
use reqwest::{header, redirect::Policy, Client, Url};
use reqwest_cookie_store::{CookieStore, CookieStoreMutex};
use std::{sync::Arc, time::Duration};

const MAX_BODY_BYTES: usize = 2_000_000;

/// A private HTTP session for exactly one login attempt.
pub struct HttpSession {
    client: Client,
    cookies: Arc<CookieStoreMutex>,
}
impl HttpSession {
    pub fn on_interface(address: std::net::Ipv4Addr) -> Result<Self, Error> {
        // No packets are sent. This only checks local address availability;
        // a successful bind does not prove that VPN/firewall policy allows traffic.
        let socket = tokio::net::TcpSocket::new_v4().map_err(Error::InterfaceBinding)?;
        socket
            .bind(std::net::SocketAddrV4::new(address, 0).into())
            .map_err(Error::InterfaceBinding)?;
        drop(socket);
        Self::build(Some(address))
    }
    pub fn new() -> Result<Self, Error> {
        Self::build(None)
    }
    fn build(address: Option<std::net::Ipv4Addr>) -> Result<Self, Error> {
        // Reuse the updater's ring provider, without pulling in another TLS
        // implementation. An already configured process provider wins.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let cookies = Arc::new(CookieStoreMutex::new(CookieStore::default()));
        let client = Client::builder()
            .local_address(address.map(std::net::IpAddr::V4))
            .cookie_provider(cookies.clone())
            .redirect(Policy::none())
            .retry(reqwest::retry::never())
            .no_proxy()
            .connect_timeout(Duration::from_secs(5))
            .read_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(8))
            .build()?;
        Ok(Self { client, cookies })
    }

    fn csrf_token(&self, target: &Url) -> Result<Option<header::HeaderValue>, Error> {
        let jar = self.cookies.lock().map_err(|_| Error::Session)?;
        let token = jar
            .matches(target)
            .into_iter()
            .filter(|cookie| cookie.name() == "XSRF-TOKEN" && !cookie.http_only().unwrap_or(false))
            .max_by_key(|cookie| cookie.path.as_ref().len());
        token
            .map(|cookie| {
                // decodeURIComponent semantics: literal '+' is not a space.
                let decoded = percent_encoding::percent_decode_str(cookie.value())
                    .decode_utf8()
                    .map_err(|_| Error::Session)?;
                let mut value =
                    header::HeaderValue::from_str(&decoded).map_err(|_| Error::Session)?;
                value.set_sensitive(true);
                Ok(value)
            })
            .transpose()
    }
}
impl Transport for HttpSession {
    async fn exchange(&self, url: &str, body: Option<&str>) -> Result<Reply, Error> {
        let target = Url::parse(url).map_err(|_| Error::Session)?;
        let request = if let Some(body) = body {
            let mut request = self
                .client
                .post(target.clone())
                .body(body.to_owned())
                .header(header::CONTENT_TYPE, "application/json")
                .header(header::ACCEPT, "application/json, text/plain, */*")
                .header(header::ORIGIN, PORTAL_ORIGIN)
                .header(header::REFERER, PORTAL_URL);
            if let Some(token) = self.csrf_token(&target)? {
                request = request.header("X-XSRF-TOKEN", token);
            }
            request
        } else {
            self.client
                .get(target)
                .header(header::ACCEPT, "text/html, text/plain")
        };
        let mut response = request
            .header(header::CACHE_CONTROL, "no-cache")
            .send()
            .await?;
        let code = response.status().as_u16();
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            if chunk.len() > MAX_BODY_BYTES - bytes.len() {
                return Err(Error::ResponseTooLarge);
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok(Reply {
            code,
            body: String::from_utf8_lossy(&bytes).into_owned(),
        })
    }
}
