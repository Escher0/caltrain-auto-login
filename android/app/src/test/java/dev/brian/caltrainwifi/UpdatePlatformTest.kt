// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.app.job.JobInfo
import android.app.job.JobParameters
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class UpdatePlatformTest {
    class DeferredService : UpdateJobService() {
        var stopped = 0
        val callbacks = mutableListOf<() -> Unit>()
        override fun startCheck(finished: () -> Unit): UpdateRequest {
            callbacks += finished
            return UpdateRequest({ stopped++ }, UpdateReason.FAILURE, finished)
        }
    }
    @Test fun `stopping an update job cancels it and stale completion cannot finish a new job`() {
        val service = Robolectric.buildService(DeferredService::class.java).create().get()
        val first = Shadow.newInstanceOf(JobParameters::class.java)
        val second = Shadow.newInstanceOf(JobParameters::class.java)
        assertTrue(service.onStartJob(first))
        assertFalse(service.onStopJob(first))
        assertEquals(1,service.stopped)
        assertTrue(service.onStartJob(second))
        service.callbacks[0]()
        assertFalse(shadowOf(service).isJobFinished)
        service.callbacks[1]()
        assertTrue(shadowOf(service).isJobFinished)
    }
    private lateinit var app: Application
    private val source=ReleaseSource("owner/app")
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication()
        UpdatePreferences(app).prefs.edit().clear().commit()
        app.getSystemService(JobScheduler::class.java).cancelAll()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun `failures queue one network job and honor automatic preference and throttle`() {
        val scheduler=app.getSystemService(JobScheduler::class.java)
        assertTrue(FailureUpdateJob.enqueue(app, source, 100_000_000L))
        assertTrue(FailureUpdateJob.enqueue(app, source, 100_000_001L))
        assertEquals(1,scheduler.allPendingJobs.size)
        assertEquals(JobInfo.NETWORK_TYPE_ANY,scheduler.allPendingJobs.single().networkType)
        scheduler.cancelAll()
        UpdatePreferences(app).lastAttempt=100_000_000L
        assertFalse(FailureUpdateJob.enqueue(app,source,100_000_001L))
        UpdatePreferences(app).automatic=false
        assertFalse(FailureUpdateJob.enqueue(app,source,200_000_000L))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `no configured source never schedules a job`() {
        assertFalse(FailureUpdateJob.enqueue(app,null,100_000_000L))
        assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
    }
    @Test fun `notifications require permission and deduplicate while preserving an in app offer`() {
        val prefs=UpdatePreferences(app)
        prefs.save(source,ReleaseResult(source.release("0.2.0"),true))
        val manager=app.getSystemService(NotificationManager::class.java)
        UpdateAlerts.show(app,source,"0.1.5")
        assertEquals(0,shadowOf(manager).size())
        assertEquals("0.2.0",prefs.available(source,"0.1.5")?.version)
        assertEquals("",prefs.notifiedVersion)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateAlerts.show(app,source,"0.1.5")
        assertEquals(1,shadowOf(manager).size())
        assertEquals("0.2.0",prefs.notifiedVersion)
        manager.cancelAll()
        UpdateAlerts.show(app,source,"0.1.5")
        assertEquals(0,shadowOf(manager).size())
        prefs.automatic=false; prefs.notifiedVersion=""
        UpdateAlerts.show(app,source,"0.1.5")
        assertEquals(0,shadowOf(manager).size())
    }
}
