package dev.brian.caltrainwifi

import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class HttpSessionTest {
    @Test fun `failed requests report the hostname and original exception without changing failure semantics`() {
        val failure = UnknownHostException("resolver unavailable")
        val observed = mutableListOf<Pair<String, IOException>>()
        HttpSession(OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = throw failure
        }),
            onFailure = { host, error -> observed += host to error }).use { session ->
            val thrown = assertThrows(UnknownHostException::class.java) {
                session.exchange("https://portal.invalid/private?token=secret", "secret payload")
            }
            assertSame(failure, thrown)
        }
        assertEquals(listOf("portal.invalid"), observed.map { it.first })
        assertSame(failure, observed.single().second)
    }
    @Test fun `swallowed probe failures still reach diagnostics`() {
        val observed = mutableListOf<String>()
        HttpSession(OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = throw UnknownHostException("unavailable")
        }),
            onFailure = { host, _ -> observed += host }).use { session ->
            assertFalse(session.online())
            assertEquals("UnknownHostException", session.lastProbeResult)
        }
        assertEquals(listOf("www.msftconnecttest.com"), observed)
    }
    @Test fun `response body failures reach diagnostics without a retry`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(2_000_001)))
            val observed = mutableListOf<Pair<String, IOException>>()
            HttpSession(OkHttpClient.Builder(), onFailure = { host, error -> observed += host to error }).use { session ->
                assertThrows(IOException::class.java) { session.exchange(server.url("/").toString(), null) }
            }
            assertEquals(1, server.requestCount)
            assertEquals(1, observed.size)
            assertTrue(observed.single().second.message!!.contains("size limit"))
        }
    }
    @Test fun `session carries cookies and decoded xsrf only on authentication post`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("entry")
                .addHeader("Set-Cookie", "session=abc; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "XSRF-TOKEN=a%2Bb%20c; Path=/"))
            server.enqueue(MockResponse().setBody("{}"))
            HttpSession(OkHttpClient.Builder()).use { session ->
                session.exchange(server.url("/en/authentication").toString(), null)
                session.exchange(server.url("/api/v1/auth/process").toString(), """{"limited":false}""")
            }
            server.takeRequest()
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("""{"limited":false}""", post.body.readUtf8())
            assertTrue(post.getHeader("Cookie")!!.contains("session=abc"))
            assertEquals("a+b c", post.getHeader("X-XSRF-TOKEN"))
        }
    }
    @Test fun `redirect is returned without following destination`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/unexpected"))
            server.enqueue(MockResponse().setBody("should not load"))
            HttpSession(OkHttpClient.Builder()).use { session ->
                assertEquals(302, session.exchange(server.url("/entry").toString(), null).code)
            }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `cookies never leak across sessions or cookie paths`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Cookie", "XSRF-TOKEN=secret; Path=/unrelated"))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse())
            HttpSession(OkHttpClient.Builder()).use { session ->
                session.exchange(server.url("/unrelated").toString(), null)
                session.exchange(server.url("/api/v1/auth/process").toString(), "{}")
            }
            HttpSession(OkHttpClient.Builder()).use { session ->
                session.exchange(server.url("/unrelated").toString(), "{}")
            }
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("X-XSRF-TOKEN"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun `closed session cannot create a later authentication call`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val session = HttpSession(OkHttpClient.Builder())
            session.close()
            assertThrows(java.io.IOException::class.java) {
                session.exchange(server.url("/api/v1/auth/process").toString(), "{}")
            }
            assertEquals(0, server.requestCount)
        }
    }

}
