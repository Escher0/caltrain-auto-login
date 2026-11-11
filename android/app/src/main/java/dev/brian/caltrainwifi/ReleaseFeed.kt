package dev.brian.caltrainwifi

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ReleaseSource(val repository: String) {
    init {
        require(repository.matches(Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")) &&
            repository.substringAfter('/') !in setOf(".", "..")) { "Invalid release repository" }
    }
    val apiUrl get() = "https://api.github.com/repos/$repository/releases?per_page=100"
    fun release(version: String): UpdateRelease {
        require(AppVersion.parse(version) != null)
        return UpdateRelease(version, "https://github.com/$repository/releases/tag/caltrain-android-v$version")
    }
}
data class UpdateRelease(val version: String, val url: String)
data class ReleaseResult(val release: UpdateRelease, val newer: Boolean)

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): AppVersion? {
            if (!value.matches(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"))) return null
            val parts = value.split('.').map { it.toInt() }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

object ReleaseFeed {
    fun parse(source: ReleaseSource, currentVersion: String, body: String): ReleaseResult {
        val current = AppVersion.parse(currentVersion) ?: throw IOException("This app version cannot be compared with releases.")
        try {
            val releases = JSONArray(body)
            var best: Pair<AppVersion, String>? = null
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.opt("draft") != false || release.opt("prerelease") != false) continue
                val tag = release.optString("tag_name")
                if (!tag.startsWith("caltrain-android-v")) continue
                val name = tag.removePrefix("caltrain-android-v")
                val version = AppVersion.parse(name) ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                val hasApk = (0 until assets.length()).any { assetIndex ->
                    val asset = assets.optJSONObject(assetIndex)
                    asset?.optString("name") == "caltrain-wifi-android-$name.apk" &&
                        asset.optString("state") == "uploaded" &&
                        (asset.opt("size") as? Number)?.toLong()?.let { it > 0 } == true
                }
                if (hasApk && (best == null || version > best.first)) best = version to name
            }
            val selected = best ?: throw IOException("No compatible Android release is available yet.")
            return ReleaseResult(source.release(selected.second), selected.first > current)
        } catch (_: JSONException) {
            throw IOException("The update service returned invalid release information.")
        }
    }
}

/** Ordinary Internet routing, deliberately separate from the captive-portal session. */
class ReleaseClient(base: OkHttpClient = OkHttpClient()) {
    private val client = base.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(12, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).build()

    fun fetch(source: ReleaseSource, current: String, url: HttpUrl = source.apiUrl.toHttpUrl(),
              onCall: (Call) -> Unit = {}): ReleaseResult {
        val call = client.newCall(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Caltrain-WiFi-Android/$current").build())
        onCall(call)
        call.execute().use { response ->
            if (response.code != 200) throw IOException("Update check failed (HTTP ${response.code}). Try again later.")
            val stream = response.body?.source() ?: throw IOException("The update service returned an empty response.")
            if (stream.request(1_000_001)) throw IOException("The update response exceeded the size limit.")
            return ReleaseFeed.parse(source, current, stream.readUtf8())
        }
    }
}
