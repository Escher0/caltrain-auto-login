// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** A private cookie jar per login attempt, shared only by that attempt's requests. */
private class SessionCookies : CookieJar {
    private val stored = mutableListOf<Cookie>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            stored.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            stored += cookie
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        stored.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return stored.filter { it.matches(url) }
    }
}

class HttpSession(
    builder: OkHttpClient.Builder,
    private val onFailure: (host: String, error: IOException) -> Unit = { _, _ -> },
) : Transport, Closeable {
    private val lock = Any()
    private var closed = false
    private var activeCall: Call? = null
    private val cookies = SessionCookies()
    private val client = builder.cookieJar(cookies).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).build()

    override fun exchange(url: String, body: String?): Reply {
        val target = url.toHttpUrl()
        val request = Request.Builder().url(target).header("Cache-Control", "no-cache")
        if (body != null) {
            request.post(body.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", PORTAL_ORIGIN).header("Referer", PORTAL_URL)
            cookies.loadForRequest(target).filter { it.name == "XSRF-TOKEN" && !it.httpOnly }
                .maxByOrNull { it.path.length }?.let {
                    // decodeURIComponent preserves literal '+', unlike URLDecoder.
                    request.header("X-XSRF-TOKEN", URLDecoder.decode(it.value.replace("+", "%2B"), "UTF-8"))
                }
        } else request.header("Accept", "text/html, text/plain")
        val call = synchronized(lock) {
            if (closed) throw IOException("Session cancelled.")
            client.newCall(request.build()).also { activeCall = it }
        }
        try { return call.execute().use { response ->
            val source = response.body?.source()
            if (source != null && source.request(2_000_001)) throw IOException("Portal response exceeded the size limit.")
            Reply(response.code, source?.readUtf8() ?: "")
        } } catch (e: IOException) {
            onFailure(target.host, e)
            throw e
        } finally {
            synchronized(lock) { if (activeCall === call) activeCall = null }
        }
    }

    var lastProbeResult: String = "Not checked"
        private set

    fun online(): Boolean = try {
        val response = exchange(PROBE_URL, null)
        val passed = response.code == 200 && response.body == "Microsoft Connect Test"
        lastProbeResult = if (passed) "Expected response received" else "HTTP ${response.code}, unexpected connectivity response"
        passed
    } catch (e: IOException) {
        lastProbeResult = e.javaClass.simpleName
        false
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            activeCall?.cancel()
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
