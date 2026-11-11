package dev.brian.caltrainwifi

import android.Manifest
import android.app.Application
import android.location.LocationManager
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
import org.robolectric.shadows.ShadowWifiInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiIdentityTest {
    private lateinit var app: Application
    private lateinit var cm: ConnectivityManager
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        cm = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
    }
    private fun caps(ssid: String) = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID(ssid)
        shadowOf(it).setTransportInfo(info)
    }
    @Test fun `only exact caltrain ssid is accepted including Android quoting`() {
        assertEquals(WifiKind.CALTRAIN, WifiIdentity.classify("Caltrain_WiFi"))
        assertEquals(WifiKind.CALTRAIN, WifiIdentity.classify("\"Caltrain_WiFi\""))
        for (ssid in listOf("Home", "Caltrain_WiFi Guest", "Caltrain_WiFi 15", "caltrain_wifi")) {
            assertEquals(WifiKind.OTHER, WifiIdentity.classify(ssid))
        }
        for (ssid in listOf(null, "", "<unknown ssid>")) assertEquals(WifiKind.UNKNOWN, WifiIdentity.classify(ssid))
    }
    @Test fun `approximate permission cannot reveal a network identity`() {
        assertFalse(WifiIdentity.hasPermission(app))
        assertEquals(WifiKind.UNKNOWN, WifiIdentity.fromCapabilities(app, caps("Caltrain_WiFi")))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(WifiIdentity.hasPermission(app))
        assertEquals(WifiKind.UNKNOWN, WifiIdentity.fromCapabilities(app, caps("Caltrain_WiFi")))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(WifiKind.CALTRAIN, WifiIdentity.fromCapabilities(app, caps("Caltrain_WiFi")))
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        assertEquals(WifiKind.UNKNOWN, WifiIdentity.fromCapabilities(app, caps("Caltrain_WiFi")))
    }
    @Test fun `observer only accepts the target network and stops allowing it after loss`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val target = ShadowNetwork.newInstance(42)
        val before = shadowOf(cm).networkCallbacks.toSet()
        WifiIdentity.observe(app, target).use { observer ->
            val callback = (shadowOf(cm).networkCallbacks - before).single()
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(43), caps("Caltrain_WiFi"))
            assertEquals(WifiKind.UNKNOWN, observer.currentKind())
            assertTrue(observer.allowed())
            callback.onCapabilitiesChanged(target, caps("Home"))
            assertFalse(observer.allowed())
            callback.onCapabilitiesChanged(target, caps("Caltrain_WiFi"))
            observer.awaitIdentity()
            assertTrue(observer.allowed())
            callback.onCapabilitiesChanged(target, caps("<unknown ssid>"))
            assertTrue(observer.allowed())
            callback.onCapabilitiesChanged(target, caps("Home"))
            assertFalse(observer.allowed())
            callback.onLost(target)
            assertFalse(observer.allowed())
            observer.close()
            assertFalse(observer.allowed())
        }
        assertEquals(before, shadowOf(cm).networkCallbacks)
    }
    @Test fun `location disabled invalidates a previously identified network`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val target = ShadowNetwork.newInstance(42)
        WifiIdentity.observe(app, target).use { observer ->
            shadowOf(cm).networkCallbacks.single().onCapabilitiesChanged(target, caps("Caltrain_WiFi"))
            assertTrue(observer.allowed())
            shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
            assertEquals(WifiKind.UNKNOWN, observer.currentKind())
            assertTrue(observer.allowed())
        }
    }
}
