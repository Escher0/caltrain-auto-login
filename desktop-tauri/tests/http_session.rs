use caltrain_wifi::{http_session::HttpSession, portal::*};
use std::{
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;

struct Response {
    status: u16,
    headers: String,
    body: String,
    stall: bool,
}
fn response(status: u16, headers: &str, body: &str) -> Response {
    Response {
        status,
        headers: headers.into(),
        body: body.into(),
        stall: false,
    }
}
struct Server {
    url: String,
    requests: Arc<Mutex<Vec<String>>>,
    task: JoinHandle<()>,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Server {
    async fn start(responses: Vec<Response>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let requests = Arc::new(Mutex::new(vec![]));
        let seen = requests.clone();
        let task = tokio::spawn(async move {
            let mut responses = responses.into_iter();
            loop {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut request = Vec::new();
                let mut buf = [0; 4096];
                loop {
                    let count = stream.read(&mut buf).await.unwrap();
                    if count == 0 {
                        break;
                    }
                    request.extend_from_slice(&buf[..count]);
                    if let Some(end) = request.windows(4).position(|part| part == b"\r\n\r\n") {
                        let header = String::from_utf8_lossy(&request[..end]);
                        let length = header
                            .lines()
                            .find_map(|line| {
                                line.to_ascii_lowercase()
                                    .strip_prefix("content-length: ")
                                    .and_then(|n| n.parse::<usize>().ok())
                            })
                            .unwrap_or(0);
                        if request.len() >= end + 4 + length {
                            break;
                        }
                    }
                }
                seen.lock()
                    .unwrap()
                    .push(String::from_utf8(request).unwrap());
                let reply = responses
                    .next()
                    .unwrap_or_else(|| response(500, "", "unexpected request"));
                if reply.stall {
                    std::future::pending::<()>().await;
                }
                let bytes = format!(
                    "HTTP/1.1 {} Test\r\nContent-Length: {}\r\nConnection: close\r\n{}\r\n{}",
                    reply.status,
                    reply.body.len(),
                    reply.headers,
                    reply.body
                );
                let _ = stream.write_all(bytes.as_bytes()).await;
            }
        });
        Self {
            url,
            requests,
            task,
        }
    }
    async fn wait_for_request(&self) {
        tokio::time::timeout(Duration::from_secs(2), async {
            while self.requests.lock().unwrap().is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
    }
}
fn header<'a>(request: &'a str, name: &str) -> Option<&'a str> {
    request
        .split("\r\n\r\n")
        .next()
        .unwrap()
        .lines()
        .find_map(|line| {
            let (key, value) = line.split_once(':')?;
            key.eq_ignore_ascii_case(name).then(|| value.trim())
        })
}

#[tokio::test]
async fn bound_session_uses_the_selected_source_address() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let session = HttpSession::on_interface("127.0.0.2".parse().unwrap()).unwrap();
    let server = async {
        let (mut socket, peer) = listener.accept().await.unwrap();
        let mut buf = [0; 4096];
        assert!(socket.read(&mut buf).await.unwrap() > 0);
        socket
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .await
            .unwrap();
        peer.ip()
    };
    let (reply, peer) = tokio::join!(session.exchange(&url, None), server);
    reply.unwrap();
    assert_eq!(peer, "127.0.0.2".parse::<std::net::IpAddr>().unwrap());
}

#[tokio::test]
async fn post_carries_private_cookies_decoded_csrf_and_portal_headers() {
    let server=Server::start(vec![response(200,"Set-Cookie: session=abc; Path=/; HttpOnly\r\nSet-Cookie: XSRF-TOKEN=a%2Bb+c%20d; Path=/\r\n","entry"),response(200,"","{}"),response(200,"","landing")]).await;
    let session = HttpSession::new().unwrap();
    session
        .exchange(&format!("{}/en/authentication", server.url), None)
        .await
        .unwrap();
    session
        .exchange(
            &format!("{}/api/v1/auth/process", server.url),
            Some(r#"{"limited":false}"#),
        )
        .await
        .unwrap();
    session
        .exchange(&format!("{}/landing", server.url), None)
        .await
        .unwrap();
    let requests = server.requests.lock().unwrap();
    let post = &requests[1];
    assert!(post.starts_with("POST /api/v1/auth/process "));
    assert!(post.ends_with(r#"{"limited":false}"#));
    assert!(header(post, "Cookie").unwrap().contains("session=abc"));
    assert_eq!(header(post, "X-XSRF-TOKEN"), Some("a+b+c d"));
    assert_eq!(
        header(post, "Origin"),
        Some("https://caltrain.passengerwifi.com")
    );
    assert_eq!(
        header(post, "Referer"),
        Some("https://caltrain.passengerwifi.com/en/authentication")
    );
    assert_eq!(header(post, "Content-Type"), Some("application/json"));
    assert_eq!(header(&requests[2], "X-XSRF-TOKEN"), None);
}

#[tokio::test]
async fn csrf_uses_longest_matching_path_ignores_httponly_and_expired_and_does_not_cross_attempts()
{
    let cookies="Set-Cookie: XSRF-TOKEN=root; Path=/\r\nSet-Cookie: XSRF-TOKEN=chosen; Path=/api\r\nSet-Cookie: XSRF-TOKEN=hidden; Path=/api/v1; HttpOnly\r\nSet-Cookie: XSRF-TOKEN=wrong; Path=/elsewhere\r\nSet-Cookie: XSRF-TOKEN=expired; Path=/api/v1/auth; Max-Age=0\r\nSet-Cookie: unrelated=secret; Domain=other.example; Path=/\r\n";
    let server = Server::start(vec![
        response(200, cookies, "entry"),
        response(200, "", "{}"),
        response(200, "", "{}"),
    ])
    .await;
    let session = HttpSession::new().unwrap();
    session
        .exchange(&format!("{}/entry", server.url), None)
        .await
        .unwrap();
    session
        .exchange(&format!("{}/api/v1/auth/process", server.url), Some("{}"))
        .await
        .unwrap();
    HttpSession::new()
        .unwrap()
        .exchange(&format!("{}/api/v1/auth/process", server.url), Some("{}"))
        .await
        .unwrap();
    let requests = server.requests.lock().unwrap();
    assert_eq!(header(&requests[1], "X-XSRF-TOKEN"), Some("chosen"));
    let cookie = header(&requests[1], "Cookie").unwrap();
    for disallowed in ["expired", "wrong", "unrelated"] {
        assert!(!cookie.contains(disallowed));
    }
    assert_eq!(header(&requests[2], "Cookie"), None);
    assert_eq!(header(&requests[2], "X-XSRF-TOKEN"), None);
}

#[tokio::test]
async fn redirect_and_failed_post_are_returned_without_following_or_retrying() {
    let server = Server::start(vec![
        response(302, "Location: /unexpected\r\n", "redirect"),
        response(503, "Retry-After: 0\r\n", "try again"),
    ])
    .await;
    let session = HttpSession::new().unwrap();
    assert_eq!(
        session
            .exchange(&format!("{}/entry", server.url), None)
            .await
            .unwrap()
            .code,
        302
    );
    assert_eq!(
        session
            .exchange(&format!("{}/auth", server.url), Some("{}"))
            .await
            .unwrap()
            .code,
        503
    );
    assert_eq!(server.requests.lock().unwrap().len(), 2);
}

#[tokio::test]
async fn oversized_response_is_rejected() {
    let server = Server::start(vec![response(200, "", &"x".repeat(2_000_001))]).await;
    assert!(matches!(
        HttpSession::new()
            .unwrap()
            .exchange(&server.url, None)
            .await,
        Err(Error::ResponseTooLarge)
    ));
}

#[tokio::test]
async fn cancellation_interrupts_a_real_pending_http_request_without_waiting_for_timeout() {
    let mut stalled = response(200, "", "");
    stalled.stall = true;
    let server = Server::start(vec![stalled]).await;
    struct Routed {
        session: HttpSession,
        url: String,
    }
    impl Transport for Routed {
        async fn exchange(&self, _url: &str, body: Option<&str>) -> Result<Reply, Error> {
            self.session.exchange(&self.url, body).await
        }
    }
    let session = Routed {
        session: HttpSession::new().unwrap(),
        url: server.url.clone(),
    };
    let cancel = CancellationToken::new();
    let attempt = login(&session, &cancel, || true, |_| {}, VERIFICATION_TIMEOUT);
    let trigger = async {
        server.wait_for_request().await;
        cancel.cancel();
    };
    let (result, ()) = tokio::time::timeout(Duration::from_secs(3), async {
        tokio::join!(attempt, trigger)
    })
    .await
    .unwrap();
    assert!(matches!(result, Err(Error::Cancelled)));
    assert_eq!(server.requests.lock().unwrap().len(), 1);
}
