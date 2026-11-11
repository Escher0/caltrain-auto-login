package dev.brian.caltrainwifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class WifiKind { CALTRAIN, OTHER, UNKNOWN }

/** Reads only the connected network's name. Never scans or requests coordinates. */
object WifiIdentity {
    fun supported() = Build.VERSION.SDK_INT >= 31
    fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun hasPermission(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun readable(context: Context) = supported() && granted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
        context.getSystemService(LocationManager::class.java).isLocationEnabled
    fun classify(ssid: String?): WifiKind {
        val name = ssid?.removeSurrounding("\"")
        return when (name) {
            null, "", "<unknown ssid>" -> WifiKind.UNKNOWN
            "Caltrain_WiFi" -> WifiKind.CALTRAIN
            else -> WifiKind.OTHER
        }
    }
    fun fromCapabilities(context: Context, caps: NetworkCapabilities?): WifiKind {
        if (!readable(context) || caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return WifiKind.UNKNOWN
        return classify((caps.transportInfo as? WifiInfo)?.ssid)
    }
    fun request(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()

    fun callback(context: Context, changed: (Network, NetworkCapabilities) -> Unit,
                 lost: (Network) -> Unit): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(
            if (hasPermission(context)) FLAG_INCLUDE_LOCATION_INFO else 0) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(network, caps)
            override fun onLost(network: Network) = lost(network)
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(network, caps)
            override fun onLost(network: Network) = lost(network)
        }

    fun observe(context: Context, network: Network) = WifiIdentityObserver(context.applicationContext, network)
}

/** Scoped to one login attempt, including its background permission context. */
class WifiIdentityObserver(context: Context, network: Network) : Closeable {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val received = CountDownLatch(1)
    @Volatile private var kind = WifiKind.UNKNOWN
    @Volatile private var closed = false
    @Volatile private var lost = false
    private var registered = false
    private val callback = WifiIdentity.callback(app, { target, caps ->
        if (target == network && !closed) {
            kind = WifiIdentity.fromCapabilities(app, caps)
            received.countDown()
        }
    }, { target ->
        if (target == network) { lost = true; kind = WifiKind.UNKNOWN; received.countDown() }
    })
    init {
        try { cm.registerNetworkCallback(WifiIdentity.request(), callback); registered = true }
        catch (_: RuntimeException) { received.countDown() }
    }
    fun awaitIdentity(cancelled: () -> Boolean = { false }) {
        repeat(30) {
            if (closed || cancelled() || received.await(100, TimeUnit.MILLISECONDS)) return
        }
    }
    // Unknown identity uses the original portal checks. Losing the target Network
    // or closing the observer still stops this attempt, even with stale capabilities.
    fun allowed() = !closed && !lost && currentKind() != WifiKind.OTHER
    fun currentKind() = if (closed || lost || !WifiIdentity.readable(app)) WifiKind.UNKNOWN else kind
    override fun close() {
        closed = true
        kind = WifiKind.UNKNOWN
        received.countDown()
        if (registered) {
            try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { }
            registered = false
        }
    }
}
