package dev.brian.caltrainwifi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class PortalClientTest {
    private val html = """<script>var ce_data = {"auth_frontend":"api","auth_bandwidth":null};
        window.features = {"terms":true,"sms_authentication":false,"email_credentials":false,
        "name_credentials":false,"marketing":false,"remember":false,"limited_bandwidth":false,"success_url":"https://www.caltrain.com/wi-fi"};</script>"""
    private class Server(var entry: String) : Transport {
        val requests = mutableListOf<Pair<String, String?>>()
        var entryCode = 200
        var auth = """{"data":{"authenticated":true}}"""
        var afterGet: () -> Unit = {}
        var landingCode = 200
        var landingFails = false
        override fun exchange(url: String, body: String?): Reply {
            requests += url to body
            if (url == "https://www.caltrain.com/wi-fi") {
                if (landingFails) throw IOException("Landing unavailable")
                return Reply(landingCode, "Caltrain Wi-Fi information")
            }
            return if (body == null) Reply(entryCode, entry).also { afterGet() } else Reply(200, auth)
        }
    }
    @Test fun `fresh session posts captured payload and verifies connectivity`() {
        val server = Server(html)
        var probes = 0
        val result = PortalClient(server, online = { ++probes > 1 }).login()
        assertEquals(LoginResult.CONNECTED, result)
        assertEquals(listOf(
            "https://caltrain.passengerwifi.com/en/authentication" to null,
            "https://caltrain.passengerwifi.com/api/v1/auth/process" to """{"limited":false}""",
            "https://www.caltrain.com/wi-fi" to null
        ), server.requests)
    }
    @Test fun `already online makes no portal requests`() {
        val server = Server(html)
        assertEquals(LoginResult.ALREADY_ONLINE, PortalClient(server, online = { true }).login())
        assertTrue(server.requests.isEmpty())
    }
    @Test fun `accepted login stays distinct when verification times out`() {
        val server = Server(html)
        val client = PortalClient(server, online = { false }, verificationMs = 0)
        assertEquals(LoginResult.ACCEPTED_UNVERIFIED, client.login())
        assertEquals(1, server.requests.count { it.second != null })
    }
    @Test fun `redirected entry cannot submit authentication`() {
        val server = Server(html).apply { entryCode = 302 }
        assertThrows(IOException::class.java) { PortalClient(server, online = { false }, verificationMs = 0).login() }
        assertEquals(1, server.requests.size)
    }
    @Test fun `changed or missing configuration cannot submit authentication`() {
        for (entry in listOf(html.replace("\"sms_authentication\":false", "\"sms_authentication\":true"),
            html.replace("\"terms\":true", "\"terms\":\"true\""), "<html>Other portal</html>")) {
            val server = Server(entry)
            assertThrows(IOException::class.java) { PortalClient(server, online = { false }, verificationMs = 0).login() }
            assertEquals(1, server.requests.size)
        }
    }
    @Test fun `limited plan is preserved`() {
        val server = Server(html.replace("\"auth_bandwidth\":null", "\"auth_bandwidth\":\"limited\"")
            .replace("\"limited_bandwidth\":false", "\"limited_bandwidth\":true"))
        PortalClient(server, online = { false }, verificationMs = 0).login()
        assertEquals("""{"limited":true}""", server.requests.single { it.second != null }.second)
    }
    @Test fun `configuration parser handles quoted braces without executing scripts`() {
        val server = Server(html.replace("\"api\"", "\"api\",\"ignored\":\"braces }; and \\\" quote\""))
        assertEquals(LoginResult.ACCEPTED_UNVERIFIED,
            PortalClient(server, online = { false }, verificationMs = 0).login())
    }
    @Test fun `rejected or malformed authentication never reports acceptance`() {
        for (auth in listOf("""{"data":{"authenticated":false}}""", """{"data":{"authenticated":"true"}}""", "<html>login</html>")) {
            val server = Server(html).apply { this.auth = auth }
            assertThrows(IOException::class.java) {
                PortalClient(server, online = { false }, verificationMs = 0).login()
            }
        }
    }
    @Test fun `revoked consent while loading entry prevents post`() {
        var enabled = true
        val server = Server(html).apply { afterGet = { enabled = false } }
        assertThrows(CancellationException::class.java) {
            PortalClient(server, online = { false }, allowed = { enabled }).login()
        }
        assertEquals(1, server.requests.size)
    }
    @Test fun `revoked consent while publishing submit stage prevents post`() {
        var enabled = true
        val server = Server(html)
        assertThrows(CancellationException::class.java) {
            PortalClient(server, online = { false }, allowed = { enabled }, stage = {
                if (it.startsWith("Accepting")) enabled = false
            }).login()
        }
        assertEquals(1, server.requests.size)
    }

    @Test fun `untrusted success destination cannot submit a login`() {
        val server = Server(html.replace("https://www.caltrain.com/wi-fi", "https://other.example/"))
        assertThrows(IOException::class.java) { PortalClient(server, online = { false }, verificationMs = 0).login() }
        assertEquals(1, server.requests.size)
    }
    @Test fun `landing page failure preserves acceptance without repeating the post`() {
        val server = Server(html).apply { landingFails = true }
        assertEquals(LoginResult.ACCEPTED_UNVERIFIED,
            PortalClient(server, online = { false }, verificationMs = 0).login())
        assertEquals(1, server.requests.count { it.second != null })
        assertEquals("https://www.caltrain.com/wi-fi", server.requests.last().first)
    }

    @Test fun `only a reachable final page asks the platform to recheck and does not itself prove connectivity`() {
        for (code in listOf(200, 302, 500)) {
            val server = Server(html).apply { landingCode = code }
            var rechecks = 0
            val result = PortalClient(server, online = { false }, verificationMs = 0,
                onPublicPageReached = { rechecks++ }).login()
            assertEquals(LoginResult.ACCEPTED_UNVERIFIED, result)
            assertEquals(if (code == 200) 1 else 0, rechecks)
        }
    }

}
