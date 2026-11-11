package dev.brian.caltrainwifi

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import java.util.UUID
import android.os.Build
import android.os.PersistableBundle

object AutoLogin {
    const val JOB_ID = 7121
    const val REPAIR_JOB_ID = 7122
    const val ACTION = "dev.brian.caltrainwifi.CAPTIVE_WIFI"
    private fun pending(context: Context, generation: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        // Explicit, private receiver. Mutable so ConnectivityManager can add Network extras.
        return PendingIntent.getBroadcast(context, 0,
            Intent(context, NetworkReceiver::class.java).setAction(ACTION)
                .setData(Uri.parse("caltrain-helper://listener/$generation")), flags)
    }

    private fun unregister(context: Context) {
        val settings = Settings(context)
        val generation = settings.prefs.getString("listenerGeneration", null) ?: return
        val intent = pending(context, generation)
        try { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(intent) }
        catch (_: IllegalArgumentException) { /* The system may have consumed it already. */ }
        intent.cancel()
        settings.prefs.edit().remove("listenerGeneration").apply()
    }

    @Synchronized fun register(context: Context) {
        val settings = Settings(context)
        if (!settings.consent || !settings.automatic) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            .build()
        unregister(context)
        // AOSP consumes PendingIntent listeners after delivery and releases them
        // later. A fresh identity prevents that delayed release removing our renewal.
        val generation = UUID.randomUUID().toString()
        settings.prefs.edit().putString("listenerGeneration", generation).apply()
        context.getSystemService(ConnectivityManager::class.java)
            .registerNetworkCallback(request, pending(context, generation))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(REPAIR_JOB_ID) == null) {
            val repair = JobInfo.Builder(REPAIR_JOB_ID, ComponentName(context, LoginJobService::class.java))
                .setPeriodic(15 * 60_000L).setPersisted(true).build()
            check(scheduler.schedule(repair) == JobScheduler.RESULT_SUCCESS) { "Listener repair could not be scheduled." }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val settings = Settings(context)
        if (enabled) {
            check(settings.consent) { "Accept automatic terms consent first." }
            settings.automatic = true
            settings.record("Automatic sign-in enabled. Waiting for captive Wi-Fi.")
            try { register(context) }
            catch (e: Exception) { setEnabled(context, false); throw e }
        } else {
            val wasEnabled = settings.automatic
            settings.automatic = false
            if (wasEnabled) settings.record("Automatic sign-in disabled.")
            unregister(context)
            context.getSystemService(JobScheduler::class.java).apply {
                cancel(JOB_ID)
                cancel(REPAIR_JOB_ID)
            }
            settings.prefs.edit().remove("lastAutoAt").remove("lastAutoNetwork").apply()
            LoginRunner.cancel()
        }
    }

    @Synchronized fun onNetwork(context: Context, network: Network) {
        val settings = Settings(context)
        if (!settings.automatic || !settings.consent) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!LoginRunner.isWifi(cm, network) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return
        val now = System.currentTimeMillis()
        val elapsed = now - settings.prefs.getLong("lastAutoAt", 0)
        if (settings.prefs.getLong("lastAutoNetwork", -1) == network.networkHandle && elapsed in 0..59_999) return
        val extras = PersistableBundle().apply { putLong("network", network.networkHandle) }
        fun job(expedited: Boolean): JobInfo {
            val builder = JobInfo.Builder(JOB_ID, ComponentName(context, LoginJobService::class.java)).setExtras(extras)
            // INTERNET/VALIDATED scheduling constraints can prevent captive-network jobs
            // from ever starting. The runner validates and binds the recorded Wi-Fi itself.
            if (Build.VERSION.SDK_INT >= 31 && expedited) builder.setExpedited(true)
            return builder.build()
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        var result = scheduler.schedule(job(Build.VERSION.SDK_INT >= 31))
        if (result == JobScheduler.RESULT_FAILURE && Build.VERSION.SDK_INT >= 31) result = scheduler.schedule(job(false))
        if (result == JobScheduler.RESULT_SUCCESS) {
            settings.prefs.edit().putLong("lastAutoAt", now).putLong("lastAutoNetwork", network.networkHandle).apply()
            settings.record(if (WifiIdentity.hasPermission(context))
                "Captive Wi-Fi detected. Caltrain network identification queued with Android."
            else "Captive Wi-Fi detected. Automatic attempt queued with Android.")
        } else settings.record("Android could not schedule sign-in. Open the app and tap Connect now.")
    }
}
