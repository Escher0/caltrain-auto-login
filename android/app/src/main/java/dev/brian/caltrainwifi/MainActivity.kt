package dev.brian.caltrainwifi

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.location.LocationManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var ui: ScreenComponents
    private lateinit var cm: ConnectivityManager
    private lateinit var status: TextView
    private lateinit var eyebrow: TextView
    private lateinit var detail: TextView
    private lateinit var history: TextView
    private lateinit var connect: Button
    private lateinit var automatic: Switch
    private lateinit var consent: CheckBox
    private lateinit var permission: Disclosure
    private lateinit var activity: Disclosure
    private lateinit var help: Disclosure
    private lateinit var identification: NetworkPermissionControls
    private lateinit var updates: UpdateControls
    private lateinit var screenState: ConnectionUiState
    private var rendering = false
    private var started = false
    private var locationWatching = false
    private val networks = linkedMapOf<Network, UiNetwork>()
    private val runnerListener: () -> Unit = { if (started) refresh() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (started) refresh() }
    }
    private var networkListener: ConnectivityManager.NetworkCallback? = null
    private val locationListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (started) { observeNetworks(); refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        ui = ScreenComponents(this)
        cm = getSystemService(ConnectivityManager::class.java)
        snapshotNetworks()
        val root = ui.column().apply {
            setBackgroundColor(ui.color(R.color.screen_background))
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(16))
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        heading.addView(ui.text(getString(R.string.app_name), 21f, medium = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
        root.addView(heading)
        ui.space(root, 28)

        val hero = ui.column().apply {
            background = ui.surface(24)
            setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22))
        }
        eyebrow = ui.text("", 11f, muted = true, medium = true).apply { letterSpacing = .08f }
        status = ui.text("", 30f, medium = true).apply {
            tag = "status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        detail = ui.text("", 14f, muted = true).apply { tag = "connection-detail" }
        hero.addView(eyebrow)
        ui.space(hero, 20)
        hero.addView(status)
        ui.space(hero, 14)
        hero.addView(detail)
        ui.space(hero, 22)
        connect = Button(this).apply {
            tag = "connect"
            isAllCaps = false
            textSize = 14f
            minHeight = ui.dp(48)
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            background = ui.surface(14, R.color.brand, border = false)
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(ui.color(R.color.disabled_button), ui.color(R.color.brand)))
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(ui.color(R.color.text_secondary), ui.color(R.color.on_brand))))
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { performPrimaryAction() }
        }
        hero.addView(connect, LinearLayout.LayoutParams(-1, -2))
        root.addView(hero)
        ui.space(root, 22)
        root.addView(ui.text("Connection preferences", 12f, muted = true))
        ui.space(root, 10)
        val preferences = ui.column().apply {
            background = ui.surface(20)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
        }
        val automaticRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(12))
        }
        val automaticLabel = ui.column().apply {
            addView(ui.text("Automatic sign-in", 14f, medium = true))
            ui.space(this, 5)
            addView(ui.text("Sign in when you join Caltrain Wi-Fi.", 12f, muted = true))
        }
        automaticRow.addView(automaticLabel, LinearLayout.LayoutParams(0, -2, 1f))
        automatic = Switch(this).apply {
            tag = "automatic"
            contentDescription = "Automatic sign-in"
            minimumHeight = ui.dp(48)
            minimumWidth = ui.dp(48)
            showText = false
            setPadding(ui.dp(8), 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering && checked != settings.automatic) {
                    try { AutoLogin.setEnabled(this@MainActivity, checked) }
                    catch (_: Exception) { settings.record("Could not enable automatic sign-in. Try reopening the app.") }
                    refresh()
                }
            }
        }
        automaticRow.addView(automatic)
        automaticLabel.setOnClickListener { if (automatic.isEnabled) automatic.performClick() }
        preferences.addView(automaticRow)
        ui.divider(preferences)
        permission = ui.disclosure("permission", "Portal permission",
            savedInstanceState?.getBoolean("permission-open") ?: !settings.consent, "")
        consent = CheckBox(this).apply {
            tag = "consent"
            setText(R.string.consent)
            textSize = 14f
            setTextColor(ui.color(R.color.text_primary))
            minimumHeight = ui.dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    settings.consent = checked
                    if (!checked) {
                        AutoLogin.setEnabled(this@MainActivity, false)
                        LoginRunner.cancel()
                    }
                    refresh()
                }
            }
        }
        permission.content.addView(consent)
        preferences.addView(permission.view)
        ui.divider(preferences)
        identification = NetworkPermissionControls(this, ui, savedInstanceState?.getBoolean("identification-open") ?: false)
        preferences.addView(identification.disclosure.view)
        root.addView(preferences)
        ui.space(root, 22)
        ui.divider(root)
        activity = ui.disclosure("activity", "Recent activity", savedInstanceState?.getBoolean("activity-open") ?: false)
        history = ui.text("", 13f, muted = true).apply { tag = "history"; setTextIsSelectable(true) }
        activity.content.addView(history)
        root.addView(activity.view)
        ui.divider(root)
        help = ui.disclosure("help", "VPN & connection help", savedInstanceState?.getBoolean("help-open") ?: false)
        help.content.addView(ui.text("A VPN or filtering app can prevent this helper from reaching the Wi-Fi sign-in page. If you use one, look for per-app exclusions or split tunneling and exclude Caltrain Auto Login. This helper cannot change your VPN settings.", 14f, muted = true))
        ui.space(help.content, 12)
        help.content.addView(ui.text("Using AdGuard? Open App management, select Caltrain Auto Login, and turn off “Route app traffic through AdGuard” for this app. Turning off content filtering alone may not bypass its routing.", 14f, muted = true))
        ui.space(help.content, 12)
        help.content.addView(ui.text("Using Tailscale? Open your avatar, then App-based split tunneling. Exclude Caltrain Auto Login so its traffic and DNS stay outside the VPN.", 14f, muted = true))
        ui.space(help.content, 12)
        help.content.addView(ui.text("Other examples with Android split tunneling include NordVPN, Proton VPN and ExpressVPN. Settings vary by app. Android’s “Block connections without VPN” setting can prevent direct Wi-Fi access even for excluded apps.", 14f, muted = true))
        ui.space(help.content, 12)
        help.content.addView(ui.text("If you selected a Private DNS provider in Android settings, it may be unreachable before Wi-Fi sign-in. Check those settings if DNS still fails after excluding this app from your VPN. Android’s own sign-in page may work because it has special network access.", 14f, muted = true))
        ui.space(help.content, 12)
        help.content.addView(ui.text("Android may delay automatic sign-in in the background. You can always open the app and connect manually. Reopen after force-stopping to restore automatic mode.", 14f, muted = true))
        root.addView(help.view)
        ui.divider(root)
        updates = UpdateControls(this, ui, savedInstanceState?.getBoolean("updates-open")
            ?: intent.getBooleanExtra("show-updates", false))
        root.addView(updates.disclosure.view)
        ui.space(root, 24)
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(ui.text("Unofficial Caltrain helper", 12f, muted = true), LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "About"
            textSize = 12f
            isAllCaps = false
            minWidth = ui.dp(64)
            minimumHeight = ui.dp(48)
            setTextColor(ui.color(R.color.text_secondary))
            setOnClickListener {
                @Suppress("DEPRECATION") val version = packageManager.getPackageInfo(packageName, 0).versionName
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.app_name)
                    .setMessage("Version $version\n\nAn unofficial personal helper. Not affiliated with Caltrain.\n\nAccepts the Wi-Fi portal terms with your permission. Optional location permission reads the Wi-Fi name only. No GPS coordinates, saved network names or clipboard access.")
                    .setPositiveButton("Close", null).show()
            }
        })
        root.addView(footer)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            setBackgroundColor(ui.color(R.color.screen_background))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
            addView(root)
        })
        refresh()
    }

    private fun performPrimaryAction() {
        when (screenState.action) {
            UiAction.WIFI_SETTINGS -> {
                try { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { settings.record("Open Wi-Fi settings to join Caltrain Wi-Fi.") }
            }
            UiAction.CONSENT -> revealConsent()
            UiAction.CONNECT -> {
                if (!settings.consent) { revealConsent(); return }
                val network = selectedNetwork()?.key
                if (network == null) { refresh(); return }
                LoginRunner.start(this, network, false) { if (started) refresh() }
                refresh()
            }
            UiAction.NONE -> Unit
        }
    }
    private fun revealConsent() {
        permission.setExpanded(true)
        consent.requestFocus()
        permission.content.post { permission.content.requestRectangleOnScreen(android.graphics.Rect(0, 0, permission.content.width, permission.content.height)) }
    }
    override fun onStart() {
        super.onStart()
        started = true
        updates.start()
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        LoginRunner.addUiListener(runnerListener)
        val filter = IntentFilter(LocationManager.MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(locationListener, filter, RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(locationListener, filter) }
        locationWatching = true
        if (settings.automatic && settings.consent) {
            try { AutoLogin.register(this) }
            catch (_: Exception) { settings.record("Automatic listener could not start. Toggle automatic sign-in off and on.") }
        }
        refresh()
    }
    override fun onResume() {
        super.onResume()
        observeNetworks()
        refresh()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (started) { observeNetworks(); refresh() }
    }
    override fun onStop() {
        started = false
        updates.stop()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        LoginRunner.removeUiListener(runnerListener)
        stopObservingNetworks()
        if (locationWatching) { unregisterReceiver(locationListener); locationWatching = false }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission-open", permission.expanded)
        outState.putBoolean("activity-open", activity.expanded)
        outState.putBoolean("help-open", help.expanded)
        outState.putBoolean("identification-open", identification.disclosure.expanded)
        outState.putBoolean("updates-open", updates.disclosure.expanded)
        super.onSaveInstanceState(outState)
    }
    private fun updateNetwork(network: Network, caps: NetworkCapabilities?) {
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            networks.remove(network)
        } else {
            networks[network] = UiNetwork(network.networkHandle,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                WifiIdentity.fromCapabilities(this, caps))
        }
    }
    private fun stopObservingNetworks() {
        networkListener?.let { try { cm.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) { } }
        networkListener = null
    }
    private fun observeNetworks() {
        stopObservingNetworks()
        // Synchronous snapshots only seed connectivity. Modern Android redacts SSIDs;
        // callback transportInfo replaces the seed and is scoped to each Network.
        snapshotNetworks()
        lateinit var observer: ConnectivityManager.NetworkCallback
        observer = WifiIdentity.callback(this, { network, caps -> runOnUiThread {
            if (started && networkListener === observer) { updateNetwork(network, caps); refresh() }
        } }, { network -> runOnUiThread {
            if (started && networkListener === observer) { networks.remove(network); refresh() }
        } })
        networkListener = observer
        try { cm.registerNetworkCallback(WifiIdentity.request(), observer) }
        catch (_: RuntimeException) {
            networkListener = null
            settings.record("Live Wi-Fi status is unavailable. Reopen the app to refresh.")
        }
    }
    private fun selectedNetwork() = networks.entries.sortedWith(
        compareByDescending<Map.Entry<Network, UiNetwork>> { WifiIdentity.hasPermission(this) && it.value.kind == WifiKind.CALTRAIN }
            .thenByDescending { it.value.captive }).firstOrNull()
    private fun snapshotNetworks() {
        networks.clear()
        cm.allNetworks.forEach { updateNetwork(it, cm.getNetworkCapabilities(it)) }
    }
    private fun refresh() {
        if (!::history.isInitialized) return
        // Callback payloads are authoritative. Synchronous queries during onLost or
        // onCapabilitiesChanged can still describe the state before the event.
        val current = selectedNetwork()?.value
        screenState = ConnectionUiState.create(current, settings.consent, settings.automatic, LoginRunner.presentation,
            LoginRunner.isRunning, WifiIdentity.hasPermission(this))
        rendering = true
        try {
            if (status.text.toString() != screenState.heading) status.text = screenState.heading
            eyebrow.text = screenState.eyebrow
            eyebrow.setTextColor(ui.color(when (screenState.tone) {
                UiTone.SUCCESS -> R.color.success
                UiTone.ATTENTION -> R.color.brand
                UiTone.NEUTRAL -> R.color.text_secondary
            }))
            detail.text = screenState.detail
            if (history.text.toString() != settings.history) history.text = settings.history
            connect.text = screenState.actionLabel
            connect.isEnabled = screenState.action != UiAction.NONE
            consent.isChecked = settings.consent
            automatic.isEnabled = settings.consent
            automatic.isChecked = settings.consent && settings.automatic
            permission.subtitle.text = if (settings.consent) "Terms acceptance authorized" else "Authorization required"
            permission.subtitle.setTextColor(ui.color(if (settings.consent) R.color.success else R.color.text_secondary))
            identification.refresh()
            updates.refresh()
        } finally { rendering = false }
    }
}
