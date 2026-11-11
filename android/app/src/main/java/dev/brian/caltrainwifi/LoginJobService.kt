// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.app.job.JobParameters
import android.app.job.JobService
import android.net.Network
import android.os.Handler
import android.os.Looper

open class LoginJobService : JobService() {
    private var attempt: LoginAttempt? = null
    private var current: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val settings = Settings(this)
        if (!settings.automatic || !settings.consent) return false
        if (params.jobId == AutoLogin.REPAIR_JOB_ID) {
            renewListener()
            return false
        }
        val network = try { Network.fromNetworkHandle(params.extras.getLong("network")) }
            catch (_: IllegalArgumentException) { return false }
        current = params
        attempt = startNetwork(network) {
            // A stopped attempt may unwind after its replacement has started.
            if (current === params) {
                current = null
                attempt = null
                renewListener()
                jobFinished(params, false)
            }
        }
        if (attempt == null) {
            // A replaced/cancelled request may still be unwinding on the runner.
            // Request platform backoff instead of consuming the new network's job.
            Handler(Looper.getMainLooper()).post {
                if (current === params) {
                    current = null
                    jobFinished(params, true)
                }
            }
        }
        return true
    }

    private fun renewListener() {
        try { AutoLogin.register(this) }
        catch (_: Exception) { Settings(this).record("Listener refresh failed. Open the app to restore automatic mode.") }
    }

    protected open fun startNetwork(network: Network, finished: () -> Unit): LoginAttempt? =
        LoginRunner.start(this, network, true, finished)

    override fun onStopJob(params: JobParameters): Boolean {
        if (current === params) {
            current = null
            attempt?.cancel()
            attempt = null
            Settings(this).record("Android stopped the background attempt. Open the app to retry.")
        }
        return false
    }
}
