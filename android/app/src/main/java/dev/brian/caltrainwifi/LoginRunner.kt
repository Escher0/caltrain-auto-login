package dev.brian.caltrainwifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArraySet

class LoginAttempt {
    val cancelled = AtomicBoolean(false)
    @Volatile var session: HttpSession? = null
    fun cancel() { cancelled.set(true); session?.close() }
}

object LoginRunner {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var active: LoginAttempt? = null
    @Volatile var presentation: AttemptUi? = null
        private set
    val isRunning: Boolean get() = busy.get()
    private val uiListeners = CopyOnWriteArraySet<() -> Unit>()
    fun addUiListener(listener: () -> Unit) { uiListeners.add(listener) }
    fun removeUiListener(listener: () -> Unit) { uiListeners.remove(listener) }
    private fun notifyUi() {
        Handler(Looper.getMainLooper()).post {
            uiListeners.forEach { it() }
        }
    }

    fun wifi(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.filter { isWifi(cm, it) }
            .sortedByDescending { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true }
            .firstOrNull()
    }

    fun isWifi(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun cancel() { active?.cancel() }

    fun start(context: Context, network: Network, automatic: Boolean, finished: () -> Unit): LoginAttempt? {
        return start(context, network, automatic, { FailureUpdateJob.enqueue(it) }, finished)
    }

    internal fun start(context: Context, network: Network, automatic: Boolean,
                       onFailure: (Context) -> Unit, finished: () -> Unit): LoginAttempt? {
        if (!busy.compareAndSet(false, true)) return null
        val app = context.applicationContext
        val settings = Settings(app)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val attempt = LoginAttempt()
        active = attempt
        fun record(message: String, phase: AttemptPhase = AttemptPhase.CONNECTING) {
            presentation = AttemptUi(network.networkHandle, phase, message)
            settings.record(message)
            notifyUi()
        }
        presentation = AttemptUi(network.networkHandle, AttemptPhase.CONNECTING, "Checking this Wi-Fi connection…")
        notifyUi()
        executor.execute {
            var identity: WifiIdentityObserver? = null
            var eligibleAttemptStarted = false
            var signInFailed = false
            var step = "Checking direct Wi-Fi access"
            var lastDiagnostic: String? = null
            var reportedFailure: IOException? = null
            fun diagnose(host: String?, error: IOException) {
                reportedFailure = error
                if (attempt.cancelled.get()) return
                val detail = WifiDiagnostics.describe(step, host, error)
                if (detail != lastDiagnostic) {
                    settings.record(detail)
                    lastDiagnostic = detail
                    notifyUi()
                }
            }
            try {
                val baseAllowed = { !attempt.cancelled.get() && settings.consent &&
                    (!automatic || settings.automatic) && isWifi(cm, network) }
                if (!baseAllowed()) throw CancellationException()
                if (WifiIdentity.hasPermission(app)) {
                    record("Identifying this Wi-Fi network…")
                    identity = WifiIdentity.observe(app, network)
                    identity.awaitIdentity { !baseAllowed() }
                    if (!baseAllowed()) throw CancellationException()
                    if (identity.currentKind() == WifiKind.OTHER) {
                        record("Skipped sign-in: this is not Caltrain Wi-Fi.", AttemptPhase.STOPPED)
                        return@execute
                    }
                    if (!identity.allowed()) throw CancellationException()
                    if (identity.currentKind() == WifiKind.UNKNOWN)
                        record("Wi-Fi name unavailable. Continuing with the usual Caltrain portal checks.")
                }
                val allowed = { baseAllowed() && identity?.allowed() != false }
                if (!allowed()) throw CancellationException()
                eligibleAttemptStarted = true
                WifiDiagnostics.checkAccess(network)
                if (!allowed()) throw CancellationException()
                // Request this Wi-Fi for DNS/sockets, subject to Android and VPN policy.
                // A fresh pool prevents reusing connections from a previous network.
                HttpSession(OkHttpClient.Builder().socketFactory(network.socketFactory)
                    .dns(object : Dns {
                        override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                    }), onFailure = { host, error -> diagnose(host, error) }).use { session ->
                    attempt.session = session
                    val reportReachability = {
                        try {
                            cm.reportNetworkConnectivity(network, true)
                        } catch (_: RuntimeException) {
                            record("Android recheck request was unavailable; continuing Wi-Fi verification.")
                        }
                    }
                    val online = {
                        step = "Checking Internet access on Wi-Fi"
                        val validated = cm.getNetworkCapabilities(network)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                        validated || session.online().also { if (it) reportReachability() }
                    }
                    val result = PortalClient(session, online, allowed, stage = { step = it; record(it) },
                        onPublicPageReached = reportReachability).login()
                    signInFailed = result == LoginResult.ACCEPTED_UNVERIFIED
                    record(when (result) {
                        LoginResult.ALREADY_ONLINE -> "This Wi-Fi already has Internet access."
                        LoginResult.CONNECTED -> "Connected. Internet access verified on Wi-Fi."
                        LoginResult.ACCEPTED_UNVERIFIED -> "Sign-in accepted; Internet not yet verified (${session.lastProbeResult}). Try Android's Sign in to network link, then recheck."
                    }, if (result == LoginResult.ACCEPTED_UNVERIFIED) AttemptPhase.UNVERIFIED else AttemptPhase.ONLINE)
                }
            } catch (_: CancellationException) {
                record("Attempt stopped. No further sign-in requests will be sent.", AttemptPhase.STOPPED)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                record("Attempt interrupted. Tap Connect now to try again.", AttemptPhase.STOPPED)
            } catch (e: IOException) {
                if (attempt.cancelled.get()) record("Attempt stopped.", AttemptPhase.STOPPED)
                else {
                    signInFailed = true
                    if (reportedFailure !== e) diagnose(null, e)
                    val phase = WifiDiagnostics.failurePhase(e, cm, network)
                    record(WifiDiagnostics.guidance(phase), phase)
                }
            } catch (_: Exception) {
                signInFailed = true
                record("Could not start sign-in. Check Wi-Fi and reopen the app.", AttemptPhase.ERROR)
            } finally {
                val stillEligible = identity?.allowed() != false && isWifi(cm, network)
                identity?.close()
                attempt.session = null
                active = null
                busy.set(false)
                notifyUi()
                if (eligibleAttemptStarted && stillEligible && signInFailed && !attempt.cancelled.get() && settings.consent &&
                    (!automatic || settings.automatic)) {
                    // Queue independently of portal work; the update job uses ordinary Internet access.
                    try { onFailure(app) } catch (_: RuntimeException) { }
                }
                Handler(Looper.getMainLooper()).post(finished)
            }
        }
        return attempt
    }
}
