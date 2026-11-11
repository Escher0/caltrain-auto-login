package dev.brian.caltrainwifi

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReleaseFeedTest {
    private fun release(version: String, extra: String = "", asset: Boolean = true) = """
      {"tag_name":"caltrain-android-v$version","draft":false,"prerelease":false,
       "html_url":"https://evil.example/", "assets":${if(asset) """[{"name":"caltrain-wifi-android-$version.apk","state":"uploaded","size":123}]""" else "[]"}$extra}
    """.trimIndent()

    @Test fun `highest stable Android version wins numerically and links are canonical`() {
        val source = ReleaseSource("owner/app")
        val body = "[${release("0.1.9")},${release("0.1.10")},{\"tag_name\":\"caltrain-desktop-v99.0.0\"}]"
        val result = ReleaseFeed.parse(source, "0.1.9", body)
        assertEquals(UpdateRelease("0.1.10", "https://github.com/owner/app/releases/tag/caltrain-android-v0.1.10"), result.release)
        assertTrue(result.newer)
        assertFalse(ReleaseFeed.parse(source,"0.1.10",body).newer)
        assertFalse(ReleaseFeed.parse(source,"1.0.0",body).newer)
    }

    @Test fun `draft prerelease invalid version and missing apk cannot be offered`() {
        val invalid = listOf(release("1.0.0").replace("\"draft\":false","\"draft\":true"),
            release("1.0.0").replace("\"prerelease\":false","\"prerelease\":true"),
            release("1.0.0-beta"), release("1.0.0", asset=false),release("999999999999.0.0"),
            release("01.2.3"), release("1.0.0").replace("\"draft\":false,", ""))
        invalid.forEach { assertThrows(IOException::class.java) { ReleaseFeed.parse(ReleaseSource("o/r"),"0.1.5","[$it]") } }
        assertThrows(IOException::class.java) { ReleaseFeed.parse(ReleaseSource("o/r"),"bad","[${release("1.0.0")}]") }
    }

    @Test fun `repository configuration cannot inject an alternate origin or path`() {
        listOf("", "https://github.com/a/b", "a/b/c", "a/../b", "a/b?token=x", "a/b#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReleaseSource(it) }
        }
        assertEquals("https://api.github.com/repos/Owner/caltrain-wifi/releases?per_page=100", ReleaseSource("Owner/caltrain-wifi").apiUrl)
    }

    @Test fun `http client rejects redirects errors and oversized metadata`() {
        MockWebServer().use { server ->
            val client = ReleaseClient(OkHttpClient.Builder().build())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
            assertThrows(IOException::class.java) { client.fetch(ReleaseSource("o/r"), "0.1.5", server.url("/releases")) }
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(403))
            assertThrows(IOException::class.java) { client.fetch(ReleaseSource("o/r"), "0.1.5", server.url("/releases")) }
            server.enqueue(MockResponse().setBody("x".repeat(1_000_001)))
            assertThrows(IOException::class.java) { client.fetch(ReleaseSource("o/r"), "0.1.5", server.url("/releases")) }
            server.enqueue(MockResponse().setBody("[${release("0.2.0")}]"))
            assertTrue(client.fetch(ReleaseSource("o/r"), "0.1.5", server.url("/releases")).newer)
            val request=server.takeRequest()
            assertEquals("GET",request.method)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        }
    }
}
