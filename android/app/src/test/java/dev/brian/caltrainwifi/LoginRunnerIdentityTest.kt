package dev.brian.caltrainwifi

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginRunnerIdentityTest {
    private lateinit var app: Application
    private lateinit var cm: ConnectivityManager
    @After fun cleanup() {
        assertFalse(LoginRunner.isRunning)
        ReflectionHelpers.setStaticField(LoginRunner::class.java, "presentation", null)
    }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        cm = app.getSystemService(ConnectivityManager::class.java)
        Settings(app).prefs.edit().clear().commit()
        Settings(app).apply { consent = true; automatic = true }
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
    }
    private fun runAttempt(automatic: Boolean, grant: Boolean, ssid: String, deliverIdentity: Boolean = true): String {
        if (grant) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val network = ShadowNetwork.newInstance(42)
        val caps = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (Build.VERSION.SDK_INT >= 31)
                shadowOf(it).setTransportInfo(ShadowWifiInfo.newInstance().also { info -> shadowOf(info).setSSID(ssid) })
        }
        shadowOf(cm).setNetworkCapabilities(network, caps)
        var finished = false
        assertNotNull(LoginRunner.start(app, network, automatic) { finished = true })
        val deadline = System.nanoTime() + 5_000_000_000L
        while (LoginRunner.isRunning && System.nanoTime() < deadline) {
            if (deliverIdentity) shadowOf(cm).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, caps) }
            Thread.sleep(10)
        }
        assertFalse("The attempt must finish", LoginRunner.isRunning)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(finished)
        assertTrue("Scoped identity callback must be released", shadowOf(cm).networkCallbacks.isEmpty())
        return Settings(app).status
    }
    @Test fun `manual sign in skips identified other wifi before internet check`() {
        assertEquals("Skipped sign-in: this is not Caltrain Wi-Fi.", runAttempt(false, true, "Home"))
    }
    @Test fun `automatic sign in skips identified other wifi`() {
        assertEquals("Skipped sign-in: this is not Caltrain Wi-Fi.", runAttempt(true, true, "Home"))
    }
    @Test fun `automatic sign in with a redacted name falls back to normal internet check`() {
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(true, true, "<unknown ssid>"))
    }
    @Test fun `manual sign in with a redacted name falls back to normal internet check`() {
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(false, true, "<unknown ssid>"))
    }
    @Test fun `approximate permission falls back when the network cannot be identified`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(true, false, "Home"))
    }
    @Test fun `location off falls back when the network cannot be identified`() {
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(true, true, "Home"))
    }
    @Test fun `missing identity callback falls back after the bounded wait`() {
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(true, true, "Home", deliverIdentity = false))
    }
    @Test @Config(sdk = [29]) fun `older Android with permission falls back to normal internet check`() {
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(true, true, "Home"))
    }
    @Test fun `permission absent retains legacy already online behavior`() {
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(true, false, "Home"))
    }
    @Test fun `identified caltrain proceeds with normal internet check`() {
        assertEquals("This Wi-Fi already has Internet access.", runAttempt(true, true, "Caltrain_WiFi"))
    }
}
