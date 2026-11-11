# Shared Caltrain portal client

Plain Kotlin/JVM code used by the Android app. The package remains
`dev.brian.caltrainwifi`.

`PortalClient` and `HttpSession` own portal configuration validation, cookies/XSRF,
authentication, the final-page visit, connectivity probing and cancellation.
Android supplies network-bound DNS/sockets and platform connectivity checks.

From `android/`, run `./gradlew :shared:test` (Windows: `gradlew.bat :shared:test`).
Tests use fake responses and local HTTP servers, not the Caltrain portal.

`fixtures/portal-cases.json` contains synthetic protocol examples read by both
`ProtocolFixturesTest` and the Rust desktop client's tests. Update this corpus
when portal requirements change so both implementations agree on what may be
submitted and what counts as authentication. The desktop production client is
Rust and does not depend on this Kotlin module.
