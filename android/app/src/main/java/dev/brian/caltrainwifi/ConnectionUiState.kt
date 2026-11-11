package dev.brian.caltrainwifi

data class UiNetwork(val handle: Long, val validated: Boolean, val captive: Boolean = false,
                     val kind: WifiKind = WifiKind.UNKNOWN)
enum class AttemptPhase { CONNECTING, ONLINE, UNVERIFIED, ACCESS_DENIED, PRIVATE_DNS_ERROR, DNS_ERROR, ERROR, STOPPED }
data class AttemptUi(val handle: Long, val phase: AttemptPhase, val detail: String)
enum class UiAction { WIFI_SETTINGS, CONSENT, CONNECT, NONE }
enum class UiTone { NEUTRAL, SUCCESS, ATTENTION }

data class ConnectionUiState(
    val eyebrow: String,
    val heading: String,
    val detail: String,
    val action: UiAction,
    val actionLabel: String,
    val tone: UiTone = UiTone.NEUTRAL,
    val busy: Boolean = false,
) {
    companion object {
        fun create(network: UiNetwork?, consent: Boolean, automatic: Boolean,
                   attempt: AttemptUi?, busy: Boolean, identify: Boolean = false): ConnectionUiState {
            if (network == null) return ConnectionUiState(
                "NOT CONNECTED", "Waiting for\nCaltrain Wi-Fi",
                if (automatic && consent) "Join Caltrain Wi-Fi in Android settings. Automatic sign-in is enabled."
                else "Join Caltrain Wi-Fi in Android settings to get started.",
                UiAction.WIFI_SETTINGS, "Open Wi-Fi settings")
            if (identify && network.kind == WifiKind.OTHER) return ConnectionUiState(
                "OTHER WI-FI", "Waiting for\nCaltrain Wi-Fi",
                "You’re on a different Wi-Fi network. Caltrain sign-in is paused.",
                UiAction.WIFI_SETTINGS, "Open Wi-Fi settings")
            val caltrain = identify && network.kind == WifiKind.CALTRAIN
            val current = attempt?.takeIf { it.handle == network.handle }
            if (busy) return ConnectionUiState(
                "SIGN-IN IN PROGRESS", "Connecting…",
                current?.detail ?: "Finishing the previous attempt before starting another.",
                UiAction.NONE, "Connecting…", busy = true)
            if (network.validated && !network.captive) return ConnectionUiState(
                if (caltrain) "CALTRAIN WI-FI IS ONLINE" else "WI-FI IS ONLINE",
                if (caltrain) "Connected to\nCaltrain Wi-Fi" else "You’re connected.",
                "Internet access verified on this Wi-Fi connection.",
                if (consent) UiAction.CONNECT else UiAction.CONSENT,
                if (consent) "Check again" else "Review portal permission", UiTone.SUCCESS)
            if (!consent) return ConnectionUiState(
                "SETUP", "Ready to sign in",
                "Allow this app to accept Caltrain Wi-Fi’s terms on your behalf.",
                UiAction.CONSENT, "Review portal permission")
            return when (current?.phase) {
                AttemptPhase.ONLINE -> ConnectionUiState(
                    "CHECK CONNECTION", "Check your connection",
                    if (network.captive) "Android reports that this Wi-Fi needs sign-in again."
                    else "The last check succeeded. Android hasn’t confirmed current Internet access yet.",
                    UiAction.CONNECT, "Check again", UiTone.ATTENTION)
                AttemptPhase.ACCESS_DENIED -> ConnectionUiState(
                    "SIGN-IN NEEDS ATTENTION", "Wi-Fi access\nis blocked",
                    "Android denied direct Wi-Fi access. If you use a VPN or filtering app, exclude Caltrain Auto Login from its routing. See VPN & connection help.",
                    UiAction.CONNECT, "Try again", UiTone.ATTENTION)
                AttemptPhase.PRIVATE_DNS_ERROR -> ConnectionUiState(
                    "SIGN-IN NEEDS ATTENTION", "Can’t reach\na required website",
                    "Your Private DNS provider may be unreachable before sign-in. See VPN & connection help and Recent activity.",
                    UiAction.CONNECT, "Try again", UiTone.ATTENTION)
                AttemptPhase.DNS_ERROR -> ConnectionUiState(
                    "SIGN-IN NEEDS ATTENTION", "Can’t reach\na required website",
                    "Wi-Fi DNS lookup failed. Check your connection and VPN & connection help. Recent activity shows which website failed.",
                    UiAction.CONNECT, "Try again", UiTone.ATTENTION)
                AttemptPhase.ERROR -> ConnectionUiState(
                    "SIGN-IN NEEDS ATTENTION", "Couldn’t sign in",
                    "Try again, or use Android’s sign-in page. Recent activity has the details.",
                    UiAction.CONNECT, "Try again", UiTone.ATTENTION)
                AttemptPhase.UNVERIFIED -> ConnectionUiState(
                    "CHECK CONNECTION", "Sign-in accepted",
                    "Internet access isn’t confirmed yet. Check again or open Android’s sign-in page.",
                    UiAction.CONNECT, "Check again", UiTone.ATTENTION)
                else -> ConnectionUiState(
                    if (caltrain) "CALTRAIN WI-FI DETECTED" else "WI-FI DETECTED", "Ready to sign in",
                    if (caltrain) "Connect now to accept the Caltrain Wi-Fi portal terms."
                    else "If you’re on Caltrain Wi-Fi, connect now to accept the portal terms.",
                    UiAction.CONNECT, "Connect now")
            }
        }
    }
}
