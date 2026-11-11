package dev.brian.caltrainwifi

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch

class UpdateControls(private val activity: Activity, private val ui: ScreenComponents, expanded: Boolean,
                     private val source: ReleaseSource? = UpdateChecks.source,
                     private val checker: UpdateChecker = UpdateChecks.checker(activity)) {
    val disclosure = ui.disclosure("updates", "Updates", expanded, "")
    private val prefs = UpdatePreferences(activity)
    private val status = ui.text("", 14f, muted = true).apply { tag = "updates-status" }
    private val automatic = Switch(activity).apply { text = "Check for updates automatically"; tag = "updates-automatic" }
    private val check = Button(activity).apply { text = "Check for updates"; isAllCaps = false; tag = "updates-check" }
    private val release = Button(activity).apply { text = "View update on GitHub"; isAllCaps = false; tag = "updates-release" }
    private val alerts = Button(activity).apply { text = "Enable update alerts"; isAllCaps = false; tag = "updates-alerts" }
    private var started = false
    private var rendering = false
    private var shownVersion: String? = null
    private val stateListener: () -> Unit = { activity.runOnUiThread { if (started) refresh() } }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        activity.runOnUiThread { if (started) refresh() }
    }
    init {
        disclosure.content.addView(status)
        ui.space(disclosure.content, 12)
        disclosure.content.addView(automatic)
        disclosure.content.addView(ui.text("Checks on app open, at most daily, and after sign-in failures, at most every 12 hours. Uses any working Internet connection. Downloads and installation start only when you choose.", 13f, muted = true))
        disclosure.content.addView(check)
        disclosure.content.addView(release)
        disclosure.content.addView(alerts)
        automatic.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) { UpdateChecks.setAutomatic(activity, enabled); refresh() }
        }
        check.setOnClickListener { checker.start(UpdateReason.MANUAL); refresh() }
        release.setOnClickListener {
            val offered = prefs.available(source, BuildConfig.VERSION_NAME) ?: return@setOnClickListener
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offered.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: ActivityNotFoundException) { prefs.message = "No browser is available to open the update page." }
        }
        alerts.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !prefs.prefs.getBoolean("notificationRequested", false)) {
                prefs.prefs.edit().putBoolean("notificationRequested", true).apply()
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7123)
            } else {
                try { activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)) }
                catch (_: ActivityNotFoundException) { prefs.message = "Open Android notification settings to enable update alerts." }
            }
        }
        refresh()
    }
    fun start() {
        started = true
        prefs.prefs.registerOnSharedPreferenceChangeListener(listener)
        checker.addListener(stateListener)
        checker.start(UpdateReason.OPEN)
        refresh()
    }
    fun stop() {
        started = false
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        checker.removeListener(stateListener)
    }
    fun refresh() {
        val offered = prefs.available(source, BuildConfig.VERSION_NAME)
        rendering = true
        try {
            automatic.isChecked = prefs.automatic
            automatic.isEnabled = source != null
            check.isEnabled = source != null && !checker.running
            check.text = if (checker.running) "Checking…" else "Check for updates"
            status.text = when {
                source == null -> "Update source is not configured in this build."
                checker.running -> "Checking for updates…"
                offered != null -> "Version ${offered.version} is available. Open its release page to review and download the APK."
                else -> prefs.message
            }
            release.visibility = if (offered != null) View.VISIBLE else View.GONE
            alerts.visibility = if (source != null && prefs.automatic) View.VISIBLE else View.GONE
            alerts.text = if (UpdateAlerts.allowed(activity)) "Update notification settings" else "Enable update alerts"
            disclosure.subtitle.text = when {
                source == null -> "Not configured"
                offered != null -> "Version ${offered.version} available"
                !prefs.automatic -> "Manual checks only"
                !UpdateAlerts.allowed(activity) -> "Enable alerts for update notices"
                else -> "Automatic checks enabled"
            }
            if (offered != null && shownVersion != offered.version) {
                shownVersion = offered.version
                disclosure.setExpanded(true)
            }
        } finally { rendering = false }
    }
}
