package dev.brian.caltrainwifi

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

object FailureUpdateJob {
    const val ID = 7123
    fun enqueue(context: Context, source: ReleaseSource? = UpdateChecks.source,
                now: Long = System.currentTimeMillis()): Boolean {
        val prefs = UpdatePreferences(context)
        if (source == null || !prefs.automatic) return false
        UpdateAlerts.show(context, source, BuildConfig.VERSION_NAME)
        if (!prefs.due(UpdateReason.FAILURE, now)) return false
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(ID) != null) return true
        return try {
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()) == JobScheduler.RESULT_SUCCESS
        } catch (_: RuntimeException) { false }
    }
}

open class UpdateJobService : JobService() {
    private var current: JobParameters? = null
    private var request: UpdateRequest? = null
    override fun onStartJob(params: JobParameters): Boolean {
        current = params
        request = startCheck {
            if (current === params) {
                current = null
                request = null
                jobFinished(params, false)
            }
        }
        if (request == null) { current = null; return false }
        return true
    }
    protected open fun startCheck(finished: () -> Unit): UpdateRequest? =
        UpdateChecks.checker(this).start(UpdateReason.FAILURE, finished)
    override fun onStopJob(params: JobParameters): Boolean {
        if (current === params) {
            current = null
            request?.cancel()
            request = null
        }
        return false
    }
}
