package dev.brian.caltrainwifi

import android.net.ConnectivityManager
import android.net.Network
import android.system.ErrnoException
import android.system.OsConstants
import java.net.DatagramSocket
import java.net.UnknownHostException

/** Network selection is subject to Android/VPN policy, even with an explicit Network. */
object WifiDiagnostics {
    fun checkAccess(network: Network) {
        // Binding an unconnected socket sends no packets. Always release it, including on denial.
        DatagramSocket(null).use { network.bindSocket(it) }
    }

    fun failurePhase(error: Throwable, cm: ConnectivityManager, network: Network): AttemptPhase {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        if (causes.filterIsInstance<ErrnoException>().any {
            it.errno == OsConstants.EPERM || it.errno == OsConstants.EACCES
        }) return AttemptPhase.ACCESS_DENIED
        if (causes.any { it is UnknownHostException }) {
            val strictDns = cm.getLinkProperties(network)?.privateDnsServerName != null
            return if (strictDns) AttemptPhase.PRIVATE_DNS_ERROR else AttemptPhase.DNS_ERROR
        }
        return AttemptPhase.ERROR
    }

    fun guidance(phase: AttemptPhase): String = when (phase) {
        AttemptPhase.ACCESS_DENIED -> "Android denied direct Wi-Fi access. A VPN or filtering app may be blocking it. If you use one, exclude Caltrain Auto Login from its routing. Android’s “Block connections without VPN” can also prevent access. See VPN & connection help."
        AttemptPhase.PRIVATE_DNS_ERROR -> "Wi-Fi DNS lookup failed. A Private DNS provider is configured and may be unreachable before sign-in. Check Android’s Private DNS settings and VPN & connection help."
        AttemptPhase.DNS_ERROR -> "Wi-Fi DNS lookup failed. Check your connection. If you use a VPN or filtering app, exclude Caltrain Auto Login from its routing. See VPN & connection help."
        else -> "Could not complete sign-in. Check Wi-Fi or use Android’s sign-in page. Recent activity has the details."
    }

    fun describe(step: String, host: String?, error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).joinToString(" <- ") {
            "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
        }
        // Keep local diagnostics bounded and single-line; never retain URL paths/query tokens.
        val safe = causes.replace(Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE), "[URL omitted]")
            .replace(Regex("[\\r\\n\\t]+"), " ").take(700)
        return "Network failure: step=$step; host=${host ?: "not available"}; $safe"
    }
}
