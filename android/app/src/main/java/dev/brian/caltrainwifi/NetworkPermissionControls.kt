package dev.brian.caltrainwifi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button

/** Optional OS permissions, separate from permission to accept portal terms. */
class NetworkPermissionControls(private val host: Activity, ui: ScreenComponents, expanded: Boolean) {
    val disclosure = ui.disclosure("identification", "Identify Caltrain Wi-Fi", expanded, "")
    private val description = ui.text("", 14f, muted = true).apply { tag = "identification-detail" }
    private val enable = Button(host).apply {
        tag = "identify-enable"
        isAllCaps = false
        minHeight = ui.dp(48)
        setOnClickListener { foreground() }
    }
    private val background = Button(host).apply {
        tag = "identify-background"
        text = "Allow background identification"
        isAllCaps = false
        minHeight = ui.dp(48)
        setOnClickListener {
            AlertDialog.Builder(host).setTitle("Identify Wi-Fi in the background")
                .setMessage("To identify Caltrain Wi-Fi while this app is closed, Android requires Location access set to “Allow all the time” with precise location enabled. The app reads the Wi-Fi name only; it does not request GPS coordinates.\n\nIf the name is hidden, automatic sign-in continues using the usual Caltrain portal checks.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue") { _, _ ->
                    if (Build.VERSION.SDK_INT == 29) host.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND)
                    else openAppSettings()
                }.show()
        }
    }
    init {
        disclosure.content.addView(description)
        ui.space(disclosure.content, 8)
        disclosure.content.addView(enable)
        disclosure.content.addView(background)
        refresh()
    }
    fun refresh() {
        val hasPermission = WifiIdentity.hasPermission(host)
        if (!WifiIdentity.supported()) {
            disclosure.subtitle.text = "Requires Android 12 or later"
            description.text = "Network identification requires Android 12 or later. The app continues using its original portal checks, regardless of location permission."
            enable.text = if (hasPermission) "Manage permission" else "Requires Android 12"
            enable.isEnabled = hasPermission
            background.visibility = View.GONE
            return
        }
        val precise = WifiIdentity.granted(host, Manifest.permission.ACCESS_FINE_LOCATION)
        val always = WifiIdentity.granted(host, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val location = host.getSystemService(LocationManager::class.java).isLocationEnabled
        disclosure.subtitle.text = when {
            !hasPermission -> "Optional · current behavior preserved"
            !precise -> "Using portal checks · name unavailable"
            !location -> "Using portal checks · Location is off"
            !always -> "Enabled · background identification limited"
            else -> "Network identification enabled"
        }
        description.text = when {
            !hasPermission -> "Optional: allow Android’s location permission to identify Caltrain and skip other Wi-Fi networks. Without a readable name, the app uses its usual Caltrain portal checks. No GPS coordinates or network names are saved."
            !precise -> "Android needs precise location permission to reveal the Wi-Fi name. Until then, sign-in continues using the usual Caltrain portal checks."
            !location -> "Turn on Android’s Location setting to read the Wi-Fi name. While the name is unavailable, sign-in continues using the usual Caltrain portal checks."
            !always -> "Identified non-Caltrain networks are skipped. Android can hide the name while this app is closed; sign-in then uses the usual Caltrain portal checks. Background identification is optional. No GPS coordinates or network names are saved."
            else -> "Identified non-Caltrain networks are skipped. If Android hides the name, sign-in uses the usual Caltrain portal checks. The app reads only the connected Wi-Fi name, without GPS or saving network names."
        }
        enable.text = when {
            !hasPermission -> "Enable network identification"
            !precise -> "Allow precise location"
            !location -> "Open Location settings"
            else -> "Manage permission"
        }
        background.visibility = if (precise && !always) View.VISIBLE else View.GONE
    }
    private fun foreground() {
        if (!WifiIdentity.supported()) { if (WifiIdentity.hasPermission(host)) openAppSettings(); return }
        if (WifiIdentity.granted(host, Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (!host.getSystemService(LocationManager::class.java).isLocationEnabled)
                host.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else openAppSettings()
            return
        }
        val prefs = dev.brian.caltrainwifi.Settings(host).prefs
        if (prefs.getBoolean("identificationAsked", false) &&
            !host.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            openAppSettings()
        } else {
            prefs.edit().putBoolean("identificationAsked", true).apply()
            host.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_FOREGROUND)
        }
    }
    private fun openAppSettings() {
        host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.packageName}")))
    }
    companion object { const val REQUEST_FOREGROUND = 41; const val REQUEST_BACKGROUND = 42 }
}
