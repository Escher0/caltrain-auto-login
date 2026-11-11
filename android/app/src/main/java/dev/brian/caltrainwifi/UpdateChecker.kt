package dev.brian.caltrainwifi

import android.content.Context
import okhttp3.Call
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArraySet

enum class UpdateReason(val interval: Long) { MANUAL(0), OPEN(24*60*60*1000L), FAILURE(12*60*60*1000L) }

class UpdatePreferences(context: Context) {
    val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }
    var lastAttempt: Long
        get() = prefs.getLong("lastAttempt", 0)
        set(value) { prefs.edit().putLong("lastAttempt", value).apply() }
    var message: String
        get() = prefs.getString("message", "No update check yet.")!!
        set(value) { prefs.edit().putString("message", value).apply() }
    var notifiedVersion: String
        get() = prefs.getString("notified", "")!!
        set(value) { prefs.edit().putString("notified", value).apply() }

    fun due(reason: UpdateReason, now: Long): Boolean = reason == UpdateReason.MANUAL ||
        (automatic && (lastAttempt == 0L || (now >= lastAttempt && now-lastAttempt >= reason.interval)))

    fun available(source: ReleaseSource?, current: String): UpdateRelease? {
        if (source == null || prefs.getString("repository", "") != source.repository) return null
        val name = prefs.getString("available", "")!!
        val version = AppVersion.parse(name) ?: return null
        val installed = AppVersion.parse(current) ?: return null
        return if (version > installed) source.release(name) else null
    }

    fun save(source: ReleaseSource, result: ReleaseResult) {
        prefs.edit().putString("repository", source.repository)
            .putString("available", if (result.newer) result.release.version else "")
            .putString("message", if (result.newer) "Update check completed."
                else "You’re up to date.").apply()
    }
}

class UpdateRequest internal constructor(private val stop: (UpdateRequest) -> Unit,
                                         internal val reason: UpdateReason,
                                         internal val finished: () -> Unit) {
    @Volatile internal var cancelled = false
    @Volatile internal var call: Call? = null
    fun cancel() = stop(this)
    internal fun attach(call: Call) { this.call = call; if (cancelled) call.cancel() }
}

/** One bounded request at a time. No Activity references or captive-network binding. */
class UpdateChecker(private val prefs: UpdatePreferences, private val source: ReleaseSource?,
                    private val current: String, private val clock: () -> Long,
                    private val online: () -> Boolean, private val executor: Executor,
                    private val deliver: (() -> Unit) -> Unit,
                    private val fetch: ((Call) -> Unit) -> ReleaseResult,
                    private val notifyAvailable: () -> Unit) {
    @Volatile private var active: UpdateRequest? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { deliver { listeners.forEach { it() } } }
    val running: Boolean get() = active != null

    @Synchronized fun start(reason: UpdateReason, finished: () -> Unit = {}): UpdateRequest? {
        if (active != null) return null
        if (source == null) {
            if (reason == UpdateReason.MANUAL) prefs.message = "Update source is not configured in this build."
            return null
        }
        val now = clock()
        if (!prefs.due(reason, now)) return null
        if (!online()) {
            if (reason == UpdateReason.MANUAL) prefs.message = "Connect to the Internet to check for updates."
            return null
        }
        val request = UpdateRequest(::cancel, reason, finished)
        active = request
        // Failed requests also consume the automatic-check interval.
        prefs.lastAttempt = now
        changed()
        executor.execute {
            if (!request.cancelled) {
                val result = runCatching { fetch(request::attach) }
                deliver { complete(request, result) }
            }
        }
        return request
    }

    @Synchronized private fun complete(request: UpdateRequest, result: Result<ReleaseResult>) {
        if (active !== request || request.cancelled) return
        active = null
        result.fold(onSuccess = {
            prefs.save(source!!, it)
            if (it.newer && request.reason != UpdateReason.MANUAL && prefs.automatic) notifyAvailable()
        }, onFailure = { prefs.message = "Couldn’t check for updates. Check Internet access and try again." })
        changed()
        request.finished()
    }

    @Synchronized private fun cancel(request: UpdateRequest) {
        if (active !== request) return
        request.cancelled = true
        request.call?.cancel()
        active = null
        prefs.message = "Update check interrupted. Try again."
        changed()
        deliver(request.finished)
    }

    fun cancelAutomatic() { active?.takeIf { it.reason != UpdateReason.MANUAL }?.cancel() }
}
