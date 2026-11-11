package dev.brian.caltrainwifi

import android.app.job.JobParameters
import android.net.Network
import android.os.Looper
import android.os.PersistableBundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginJobServiceTest {
    class BusyService : LoginJobService() {
        override fun startNetwork(network: Network, finished: () -> Unit): LoginAttempt? = null
    }
    class DeferredService : LoginJobService() {
        val callbacks = mutableListOf<() -> Unit>()
        override fun startNetwork(network: Network, finished: () -> Unit): LoginAttempt {
            callbacks += finished
            return LoginAttempt()
        }
    }
    private fun params(id: Int): JobParameters = Shadow.newInstanceOf(JobParameters::class.java).also {
        ReflectionHelpers.setField(it, "jobId", AutoLogin.JOB_ID)
        ReflectionHelpers.setField(it, "extras", PersistableBundle().apply {
            putLong("network", ShadowNetwork.newInstance(id).networkHandle)
        })
    }
    @Test fun `busy runner requests job reschedule instead of losing new network`() {
        val service = Robolectric.buildService(BusyService::class.java).create().get()
        Settings(service).apply { consent = true; automatic = true }
        assertTrue(service.onStartJob(params(123)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isJobFinished)
        assertTrue(shadowOf(service).isRescheduleNeeded)
    }
    @Test fun `stopped attempt cannot finish replacement job`() {
        val service = Robolectric.buildService(DeferredService::class.java).create().get()
        Settings(service).apply { consent = true; automatic = true }
        val first = params(123)
        service.onStartJob(first)
        service.onStopJob(first)
        service.onStartJob(params(124))
        service.callbacks[0]()
        assertFalse(shadowOf(service).isJobFinished)
        service.callbacks[1]()
        assertTrue(shadowOf(service).isJobFinished)
    }
}
