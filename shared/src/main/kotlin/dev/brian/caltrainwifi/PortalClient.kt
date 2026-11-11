package dev.brian.caltrainwifi

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

const val PORTAL_ORIGIN = "https://caltrain.passengerwifi.com"
const val PORTAL_URL = "$PORTAL_ORIGIN/en/authentication"
const val AUTH_URL = "$PORTAL_ORIGIN/api/v1/auth/process"
const val LANDING_URL = "https://www.caltrain.com/wi-fi"
const val PROBE_URL = "http://www.msftconnecttest.com/connecttest.txt"

data class Reply(val code: Int, val body: String)
fun interface Transport { fun exchange(url: String, body: String?): Reply }
enum class LoginResult { ALREADY_ONLINE, CONNECTED, ACCEPTED_UNVERIFIED }

class PortalClient(
    private val transport: Transport,
    private val online: () -> Boolean,
    private val allowed: () -> Boolean = { true },
    private val verificationMs: Long = 45_000,
    private val stage: (String) -> Unit = {},
    private val onPublicPageReached: () -> Unit = {},
) {
    private fun checkAllowed() {
        if (Thread.currentThread().isInterrupted || !allowed()) throw CancellationException()
    }

    fun login(): LoginResult {
        checkAllowed()
        stage("Checking this Wi-Fi connection…")
        if (online()) return LoginResult.ALREADY_ONLINE
        checkAllowed()
        stage("Loading the Caltrain portal…")
        val entry = transport.exchange(PORTAL_URL, null)
        if (entry.code in 300..399) {
            if (online()) return LoginResult.ALREADY_ONLINE
            throw IOException("Portal redirected; no sign-in was submitted.")
        }
        if (entry.code != 200) throw IOException("Portal returned HTTP ${entry.code}.")
        val payload = configuration(entry.body)
        checkAllowed()
        stage("Accepting terms and signing in…")
        checkAllowed()
        val response = transport.exchange(AUTH_URL, payload)
        if (response.code !in 200..299) throw IOException("Sign-in returned HTTP ${response.code}.")
        val authenticated = try { JSONObject(response.body).optJSONObject("data")?.opt("authenticated") }
            catch (_: Exception) { throw IOException("Sign-in response was not valid JSON.") }
        if (authenticated != true) throw IOException("The portal did not authenticate this device.")
        stage("Sign-in accepted. Loading Caltrain's final page…")
        checkAllowed()
        val landing = try { transport.exchange(LANDING_URL, null) }
            catch (e: IOException) {
                stage("Final page unavailable (${e.javaClass.simpleName}). Continuing Internet check.")
                null
            }
        if (landing != null) {
            stage("Final page returned HTTP ${landing.code}.")
            if (landing.code in 200..299) {
                checkAllowed()
                // This is only a hint to Android to run its own validation. A
                // potentially allowlisted landing page does not establish access.
                onPublicPageReached()
            }
        }
        stage("Sign-in accepted. Checking Internet access…")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(verificationMs)
        do {
            checkAllowed()
            if (online()) return LoginResult.CONNECTED
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) break
            Thread.sleep(minOf(1500, remainingMs))
        } while (System.nanoTime() < deadline)
        return LoginResult.ACCEPTED_UNVERIFIED
    }

    private fun configuration(html: String): String {
        val features = assignment(html, Regex("window\\.features\\s*=\\s*(?=\\{)"))
        val content = assignment(html, Regex("(?:var\\s+ce_data|window\\.ce_data)\\s*=\\s*(?=\\{)"))
        if (content.opt("auth_frontend") != "api" || features.opt("terms") != true ||
            listOf("sms_authentication", "email_credentials", "name_credentials", "marketing", "remember")
                .any { features.opt(it) != false } || features.opt("limited_bandwidth") !is Boolean ||
            !content.has("auth_bandwidth") || features.opt("success_url") != LANDING_URL) {
            throw IOException("Portal requirements changed. Use the normal sign-in page.")
        }
        val limited = features.opt("limited_bandwidth") == true && content.opt("auth_bandwidth") == "limited"
        return "{\"limited\":$limited}"
    }

    // Extract a balanced JSON object; never evaluate HTML or JavaScript.
    private fun assignment(html: String, pattern: Regex): JSONObject {
        val start = pattern.find(html)?.range?.last?.plus(1)
            ?: throw IOException("This is not the supported Caltrain portal.")
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return try { JSONObject(html.substring(start, i + 1)) }
                        catch (_: Exception) { throw IOException("Portal configuration is invalid.") }
                }
            }
        }
        throw IOException("Portal configuration is incomplete.")
    }
}
