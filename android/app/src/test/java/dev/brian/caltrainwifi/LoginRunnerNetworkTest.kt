// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [LoginRunnerNetworkTest.PolicyNetwork::class])
class LoginRunnerNetworkTest {
    @Implements(Network::class)
    class PolicyNetwork : ShadowNetwork() {
        @Implementation override fun bindSocket(socket: DatagramSocket) {
            boundSocket = socket
            onBind()
            bindError?.let { throw SocketException("Binding socket failed").apply {
                initCause(ErrnoException("android_setsocknetwork", it))
            } }
        }
        @Implementation fun getAllByName(host: String): Array<InetAddress> {
            lookups += host
            throw UnknownHostException("Unable to resolve host $host").apply {
                initCause(IllegalStateException("resolver unavailable"))
            }
        }
        companion object {
            var bindError: Int? = null
            var boundSocket: DatagramSocket? = null
            var onBind: () -> Unit = {}
            val lookups = mutableListOf<String>()
        }
    }
    private lateinit var app: Application
    private lateinit var cm: ConnectivityManager
    private lateinit var network: Network
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        cm = app.getSystemService(ConnectivityManager::class.java)
        Settings(app).prefs.edit().clear().commit()
        Settings(app).apply { consent = true; automatic = true }
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        network = ShadowNetwork.newInstance(42)
        shadowOf(cm).setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        })
        PolicyNetwork.bindError = null
        PolicyNetwork.boundSocket = null
        PolicyNetwork.onBind = {}
        PolicyNetwork.lookups.clear()
    }
    @After fun cleanup() {
        assertFalse(LoginRunner.isRunning)
        ReflectionHelpers.setStaticField(LoginRunner::class.java, "presentation", null)
    }
    private fun runAttempt(onFailure: (android.content.Context) -> Unit = {}): AttemptUi {
        var finished = false
        assertNotNull(LoginRunner.start(app, network, automatic = true, onFailure = onFailure) { finished = true })
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!finished && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse("Attempt must finish", LoginRunner.isRunning)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(finished)
        return LoginRunner.presentation!!
    }
    @Test fun `policy denial stops before DNS and closes the preflight socket`() {
        PolicyNetwork.bindError = OsConstants.EPERM
        assertEquals("ACCESS_DENIED", runAttempt().phase.name)
        assertTrue(PolicyNetwork.lookups.isEmpty())
        assertTrue(PolicyNetwork.boundSocket!!.isClosed)
        assertTrue(Settings(app).history.contains("EPERM"))
    }
    @Test fun `access denied errno is handled even without a visible VPN network`() {
        PolicyNetwork.bindError = OsConstants.EACCES
        assertEquals("ACCESS_DENIED", runAttempt().phase.name)
        assertTrue(PolicyNetwork.lookups.isEmpty())
    }
    @Test fun `lost network at bind is not reported as VPN denial`() {
        PolicyNetwork.bindError = OsConstants.ENONET
        assertEquals(AttemptPhase.ERROR, runAttempt().phase)
        assertTrue(PolicyNetwork.lookups.isEmpty())
        assertTrue(PolicyNetwork.boundSocket!!.isClosed)
    }
    @Test fun `DNS failure logs hostname step and underlying cause after successful bind`() {
        assertEquals(AttemptPhase.DNS_ERROR, runAttempt().phase)
        val history = Settings(app).history
        assertTrue(history.contains("host=caltrain.passengerwifi.com"))
        assertTrue(history.contains("Loading the Caltrain portal"))
        assertTrue(history.contains("resolver unavailable"))
        assertTrue(history.contains("host=www.msftconnecttest.com"))
        assertTrue(PolicyNetwork.boundSocket!!.isClosed)
    }
    @Test fun `strict private DNS gets conditional guidance instead of blaming Tailscale`() {
        val properties = LinkProperties()
        ReflectionHelpers.callInstanceMethod<Void>(properties, "setPrivateDnsServerName",
            ReflectionHelpers.ClassParameter.from(String::class.java, "dns.example"))
        shadowOf(cm).setLinkProperties(network, properties)
        val result = runAttempt()
        assertEquals("PRIVATE_DNS_ERROR", result.phase.name)
        assertTrue(result.detail.contains("Private DNS"))
    }
    @Test fun `revoked consent prevents even the preflight check`() {
        Settings(app).consent = false
        assertEquals(AttemptPhase.STOPPED, runAttempt().phase)
        assertNull(PolicyNetwork.boundSocket)
        assertTrue(PolicyNetwork.lookups.isEmpty())
    }
    @Test fun `eligible failed sign in triggers one update check but revoked consent does not`() {
        var updates = 0
        PolicyNetwork.bindError = OsConstants.EPERM
        runAttempt { updates++ }
        assertEquals(1, updates)
        Settings(app).consent = false
        runAttempt { updates++ }
        assertEquals(1, updates)
    }

    @Test fun `late identification as another network prevents a failure update check`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(android.location.LocationManager::class.java)).setLocationEnabled(true)
        PolicyNetwork.bindError = OsConstants.EPERM
        PolicyNetwork.onBind = {
            val other = ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                shadowOf(it).setTransportInfo(org.robolectric.shadows.ShadowWifiInfo.newInstance().also { info ->
                    shadowOf(info).setSSID("Home")
                })
            }
            shadowOf(cm).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, other) }
        }
        var updates = 0
        runAttempt { updates++ }
        assertEquals(0, updates)
    }
}
