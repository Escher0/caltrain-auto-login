package dev.brian.caltrainwifi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** The Rust suite reads the same synthetic inputs. No captured device tokens. */
class ProtocolFixturesTest {
    private val fixtures = JSONObject(javaClass.getResource("/portal-cases.json")!!.readText())

    @Test fun `shared configurations submit only supported payloads`() {
        val cases = fixtures.getJSONArray("portal_cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val posts = mutableListOf<String>()
            val client = PortalClient(Transport { url, body ->
                if (body != null) posts += body
                Reply(200, when (url) {
                    PORTAL_URL -> case.getString("html")
                    AUTH_URL -> """{"data":{"authenticated":true}}"""
                    else -> "Caltrain"
                })
            }, online = { false }, verificationMs = 0)
            if (case.isNull("payload")) {
                assertThrows(case.getString("name"), IOException::class.java) { client.login() }
                assertTrue(posts.isEmpty())
            } else {
                assertEquals(case.getString("name"), LoginResult.ACCEPTED_UNVERIFIED, client.login())
                assertEquals(case.getJSONObject("payload").toString(), posts.single())
            }
        }
    }

    @Test fun `shared authentication responses require boolean true`() {
        val cases = fixtures.getJSONArray("auth_cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            var loadedFinalPage = false
            val client = PortalClient(Transport { url, _ ->
                if (url == LANDING_URL) loadedFinalPage = true
                Reply(200, when (url) {
                    PORTAL_URL -> fixtures.getJSONArray("portal_cases").getJSONObject(0).getString("html")
                    AUTH_URL -> case.getString("body")
                    else -> "Caltrain"
                })
            }, online = { false }, verificationMs = 0)
            if (case.getBoolean("authenticated")) {
                assertEquals(LoginResult.ACCEPTED_UNVERIFIED, client.login())
                assertTrue(loadedFinalPage)
            } else {
                assertThrows(case.getString("name"), IOException::class.java) { client.login() }
                assertFalse(loadedFinalPage)
            }
        }
    }
}
