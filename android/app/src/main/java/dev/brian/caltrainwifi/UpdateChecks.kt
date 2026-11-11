package dev.brian.caltrainwifi

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object UpdateChecks {
    val source: ReleaseSource? get() = BuildConfig.UPDATE_REPOSITORY.takeIf { it.isNotEmpty() }?.let(::ReleaseSource)
    private val executor = Executors.newSingleThreadExecutor()
    private var owner: Application? = null
    private var instance: UpdateChecker? = null

    @Synchronized fun checker(context: Context): UpdateChecker {
        val app = context.applicationContext as Application
        if (owner !== app) {
            owner = app
            val repository = source
            val client = ReleaseClient()
            instance = UpdateChecker(UpdatePreferences(app), repository, BuildConfig.VERSION_NAME,
                System::currentTimeMillis, { online(app) }, executor,
                { Handler(Looper.getMainLooper()).post(it) },
                { attached -> client.fetch(repository!!, BuildConfig.VERSION_NAME, onCall = attached) },
                { UpdateAlerts.show(app, repository, BuildConfig.VERSION_NAME) })
        }
        return instance!!
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    fun setAutomatic(context: Context, enabled: Boolean) {
        UpdatePreferences(context).automatic = enabled
        if (!enabled) {
            checker(context).cancelAutomatic()
            context.getSystemService(android.app.job.JobScheduler::class.java).cancel(FailureUpdateJob.ID)
            context.getSystemService(android.app.NotificationManager::class.java).cancel(UpdateAlerts.ID)
        }
    }
}
