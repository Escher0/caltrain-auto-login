// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.content.Context
import java.text.DateFormat
import java.util.Date

class Settings(context: Context) {
    val prefs = context.getSharedPreferences("caltrain", Context.MODE_PRIVATE)
    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(value) { prefs.edit().putBoolean("consent", value).apply() }
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", false)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }
    val status: String get() = prefs.getString("status", "Ready for a Wi-Fi connection.")!!
    val history: String get() = prefs.getString("history", "No attempts yet.")!!

    fun record(message: String) = synchronized(logLock) {
        val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date())
        val lines = prefs.getString("history", "")!!.lines().filter { it.isNotEmpty() }
        prefs.edit().putString("status", message)
            .putString("history", (listOf("$timestamp  $message") + lines).take(24).joinToString("\n")).apply()
    }
    companion object { private val logLock = Any() }
}
