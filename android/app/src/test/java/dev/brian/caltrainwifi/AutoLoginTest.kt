package dev.brian.caltrainwifi

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoLoginTest {
    private lateinit var context: Application
    private lateinit var scheduler: JobScheduler
    private lateinit var cm: ConnectivityManager
    private fun capabilities(transport: Int, capability: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(transport)
            shadowOf(it).addCapability(capability)
        }
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Settings(context).prefs.edit().clear().commit()
        scheduler = context.getSystemService(JobScheduler::class.java)
        cm = context.getSystemService(ConnectivityManager::class.java)
    }
    @Test fun `automatic mode requires explicit consent`() {
        assertThrows(IllegalStateException::class.java) { AutoLogin.setEnabled(context, true) }
        assertFalse(Settings(context).automatic)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `captive wifi event queues network scoped job without validated internet constraint`() {
        val settings = Settings(context)
        settings.consent = true
        AutoLogin.setEnabled(context, true)
        val network = ShadowNetwork.newInstance(123)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        shadowOf(cm).setNetworkCapabilities(network, caps)
        AutoLogin.onNetwork(context, network)
        val job = scheduler.allPendingJobs.single { it.id == AutoLogin.JOB_ID }
        assertEquals(network.networkHandle, job.extras.getLong("network"))
        assertNull(job.requiredNetwork)
        AutoLogin.setEnabled(context, false)
        assertFalse(settings.automatic)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `cellular or noncaptive networks never queue a job`() {
        Settings(context).consent = true
        AutoLogin.setEnabled(context, true)
        for (caps in listOf(
            capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )) {
            val network = ShadowNetwork.newInstance(123)
            shadowOf(cm).setNetworkCapabilities(network, caps)
            AutoLogin.onNetwork(context, network)
            assertTrue(scheduler.allPendingJobs.none { it.id == AutoLogin.JOB_ID })
        }
    }
    @Test fun `late callback after disabling cannot schedule another login`() {
        Settings(context).consent = true
        AutoLogin.setEnabled(context, true)
        AutoLogin.setEnabled(context, false)
        val network = ShadowNetwork.newInstance(123)
        shadowOf(cm).setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        AutoLogin.onNetwork(context, network)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `automatic mode keeps a periodic listener repair job until disabled`() {
        Settings(context).consent = true
        AutoLogin.setEnabled(context, true)
        val repair = scheduler.allPendingJobs.singleOrNull { it.isPeriodic }
        assertNotNull("A consumed network PendingIntent needs durable listener repair", repair)
        assertTrue(repair!!.isPersisted)
        AutoLogin.setEnabled(context, false)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test fun `listener replacement has new identity so delayed old release cannot remove it`() {
        Settings(context).consent = true
        AutoLogin.setEnabled(context, true)
        val old = shadowOf(cm).networkCallbackPendingIntents.single()
        AutoLogin.register(context)
        val replacement = shadowOf(cm).networkCallbackPendingIntents.single()
        assertNotEquals(old, replacement)
        cm.unregisterNetworkCallback(old)
        assertEquals(setOf(replacement), shadowOf(cm).networkCallbackPendingIntents)
    }

}
