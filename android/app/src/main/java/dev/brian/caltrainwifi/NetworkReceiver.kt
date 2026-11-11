// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build

class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                AutoLogin.ACTION -> {
                    if (intent.data?.lastPathSegment != Settings(context).prefs.getString("listenerGeneration", null)) return
                    val network = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
                    else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
                    }
                    if (network != null) AutoLogin.onNetwork(context, network)
                }
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> AutoLogin.register(context)
            }
        } catch (_: Exception) {
            Settings(context).record("Automatic sign-in could not start. Open the app to retry.")
        }
    }
}
