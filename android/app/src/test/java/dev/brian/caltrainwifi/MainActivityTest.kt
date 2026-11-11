// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.app.Application
import android.Manifest
import android.location.LocationManager
import android.app.job.JobScheduler
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private lateinit var context: Application
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Settings(context).prefs.edit().clear().commit()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).clearAllNetworks()
    }
    private fun <T : View> MainActivity.tag(name: String): T = window.decorView.findViewWithTag(name)

    @Test @Config(sdk = [29]) fun `older Android does not invite an unusable identity permission`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        assertFalse(app.tag<Button>("identify-enable").isEnabled)
        assertEquals(View.GONE, app.tag<Button>("identify-background").visibility)
        assertTrue(app.tag<TextView>("identification-detail").text.contains("Android 12"))
        controller.pause().stop().destroy()
    }

    @Test fun `optional identification requests foreground permissions without granting portal consent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        app.tag<Button>("identify-enable").performClick()
        val request = shadowOf(app).lastRequestedPermission
        assertEquals(setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            request.requestedPermissions.toSet())
        assertFalse(Settings(context).consent)
        assertFalse(Settings(context).automatic)
        controller.pause().stop().destroy()
    }
    @Test fun `background access is offered only after precise foreground permission`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        assertEquals(View.GONE, app.tag<Button>("identify-background").visibility)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        controller.pause().resume()
        assertEquals(View.VISIBLE, app.tag<Button>("identify-background").visibility)
        controller.pause().stop().destroy()
    }
    @Test fun `permission and callback identify home wifi and revocation restores legacy status`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = ShadowNetwork.newInstance(42)
        val caps = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadowOf(it).setTransportInfo(ShadowWifiInfo.newInstance().also { info -> shadowOf(info).setSSID("Home") })
        }
        @Suppress("DEPRECATION")
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true)
        shadowOf(cm).addNetwork(network, info)
        shadowOf(cm).setNetworkCapabilities(network, caps)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        shadowOf(cm).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, caps) }
        assertEquals("Waiting for\nCaltrain Wi-Fi", app.tag<TextView>("status").text.toString())
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        controller.pause().resume()
        assertEquals("You’re connected.", app.tag<TextView>("status").text.toString())
        controller.pause().stop().destroy()
    }

    @Test fun `first run exposes unchecked consent and disables automatic mode`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        assertFalse(app.tag<CheckBox>("consent").isChecked)
        assertEquals(View.VISIBLE, app.tag<View>("permission-content").visibility)
        assertFalse(app.tag<Switch>("automatic").isEnabled)
        controller.pause().stop().destroy()
    }
    @Test fun `updates are visibly unconfigured and opening them never grants sign in consent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        app.tag<View>("updates-toggle").performClick()
        assertTrue(app.tag<TextView>("updates-status").text.contains("not configured"))
        assertFalse(app.tag<Button>("updates-check").isEnabled)
        assertEquals(View.GONE, app.tag<Button>("updates-release").visibility)
        assertFalse(Settings(context).consent)
        controller.recreate()
        assertEquals(View.VISIBLE, controller.get().tag<View>("updates-content").visibility)
        controller.pause().stop().destroy()
    }
    @Test fun `no wifi action opens settings without granting consent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        app.tag<Button>("connect").performClick()
        assertEquals(AndroidSettings.ACTION_WIFI_SETTINGS, shadowOf(app).nextStartedActivity.action)
        assertFalse(Settings(context).consent)
        controller.pause().stop().destroy()
    }
    @Test fun `revoking consent switches off automatic sign in and its jobs`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        app.tag<CheckBox>("consent").performClick()
        app.tag<Switch>("automatic").isChecked = true
        assertTrue(Settings(context).automatic)
        app.tag<CheckBox>("consent").performClick()
        assertFalse(Settings(context).automatic)
        assertFalse(app.tag<Switch>("automatic").isChecked)
        assertFalse(app.tag<Switch>("automatic").isEnabled)
        assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        controller.pause().stop().destroy()
    }
    @Test fun `activity details are initially collapsed and can be revealed`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        assertEquals(View.GONE, app.tag<View>("activity-content").visibility)
        app.tag<View>("activity-toggle").performClick()
        assertEquals(View.VISIBLE, app.tag<View>("activity-content").visibility)
        app.tag<View>("activity-toggle").performClick()
        assertEquals(View.GONE, app.tag<View>("activity-content").visibility)
        controller.pause().stop().destroy()
    }
    @Test fun `screen stop removes its network observer`() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val before = shadowOf(cm).networkCallbacks.size
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(before + 1, shadowOf(cm).networkCallbacks.size)
        controller.pause().stop().destroy()
        assertEquals(before, shadowOf(cm).networkCallbacks.size)
    }
    @Test fun `network callbacks replace connected status when wifi becomes captive or disappears`() {
        Settings(context).consent = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = ShadowNetwork.newInstance(42)
        @Suppress("DEPRECATION")
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true)
        shadowOf(cm).addNetwork(network, info)
        val caps = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowOf(cm).setNetworkCapabilities(network, caps)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = controller.get()
        assertEquals("You’re connected.", app.tag<TextView>("status").text.toString())
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        shadowOf(cm).setNetworkCapabilities(network, caps)
        shadowOf(cm).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, caps) }
        assertEquals("Connect now", app.tag<Button>("connect").text.toString())
        // The synchronous query can still contain the network when onLost arrives.
        shadowOf(cm).networkCallbacks.toList().forEach { it.onLost(network) }
        assertEquals("Open Wi-Fi settings", app.tag<Button>("connect").text.toString())
        controller.pause().stop().destroy()
    }
    @Test fun `recreation preserves disclosure choices without granting consent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().tag<View>("activity-toggle").performClick()
        controller.get().tag<View>("permission-toggle").performClick()
        controller.recreate()
        val app = controller.get()
        assertEquals(View.VISIBLE, app.tag<View>("activity-content").visibility)
        assertEquals(View.GONE, app.tag<View>("permission-content").visibility)
        assertFalse(app.tag<CheckBox>("consent").isChecked)
        assertFalse(Settings(context).consent)
        controller.pause().stop().destroy()
    }
}
