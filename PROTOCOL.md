# Captive portal protocol

This describes the implemented Caltrain flow, recovered from portal HTML and
JavaScript in November 2026 and exercised in live desktop/phone sign-in tests.
Raw browser captures, session cookies and tokens are not included in this repo.
The portal can change; unknown requirements stop automatic submission.

## Sign-in flow

1. Check whether the selected Wi-Fi connection already has Internet access.
2. GET `https://caltrain.passengerwifi.com/en/authentication` with a fresh session.
3. Extract JSON configuration from `window.features` and `ce_data` without
   executing page JavaScript. Require the known API frontend, terms enabled,
   credential/marketing/remember features disabled and the expected success URL.
4. With user consent, POST JSON to
   `https://caltrain.passengerwifi.com/api/v1/auth/process`.
   The body contains `limited`, computed from the portal's limited-bandwidth
   feature and selected bandwidth mode. Preserve the portal's choice.
5. Require a successful HTTP response and JSON `data.authenticated` equal to the
   boolean `true`.
6. GET `https://www.caltrain.com/wi-fi` using the same session.
7. Independently verify Internet access for up to 45 seconds, plus any final
   request already in progress. A successful authentication response or reachable
   landing page alone does not count as Internet access.

The connectivity probe expects HTTP 200 and exactly `Microsoft Connect Test`
from `http://www.msftconnecttest.com/connecttest.txt`. Android can also use the
selected Wi-Fi network's validated capability. Transport adapters preserve
cookies and mirror the session's XSRF token where applicable. Redirects are not
followed, and authentication POSTs are not automatically retried by the transport.

## Android's extra portal button

The inspected web frontend optionally shows a Copy URL overlay. Its button calls
a clipboard helper and starts the authentication request without waiting for
clipboard success. The native app uses the HTTP flow and does not access the
clipboard. After reaching the final page, Android receives a connectivity
recheck hint; independent validation still determines the result.

## Network routing

Android binds DNS and sockets to the selected Wi-Fi network. Desktop binds HTTP
sockets to the selected Wi-Fi IPv4 address, disables HTTP proxies and uses OS DNS.
Neither approach can override VPN/firewall policy. See platform documentation
for limitations and diagnostics.

## Related work

[bsiranosian/caltrain-wifi-autoconnect](https://github.com/bsiranosian/caltrain-wifi-autoconnect)
is another Linux implementation. The version inspected during development used
form fields rather than this JSON API flow. No code from that project was copied
into this implementation.

## Tests

[Shared fixtures](shared/fixtures/portal-cases.json) are synthetic. Kotlin and Rust
tests check configuration validation, strict authentication results and rejection
of changed portal requirements. Local HTTP tests cover sessions, redirects and
connectivity verification without contacting the train network.
