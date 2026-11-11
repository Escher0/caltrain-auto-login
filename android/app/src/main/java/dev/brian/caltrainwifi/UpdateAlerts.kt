package dev.brian.caltrainwifi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object UpdateAlerts {
    const val ID = 7123
    private const val CHANNEL = "app-updates"
    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun show(context: Context, source: ReleaseSource?, current: String) {
        val prefs = UpdatePreferences(context)
        val release = prefs.available(source, current) ?: return
        if (!prefs.automatic || !allowed(context) || prefs.notifiedVersion == release.version) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java).putExtra("show-updates", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Caltrain Auto Login update available")
            .setContentText("Version ${release.version} is available. Tap to review the update.")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        try {
            manager.notify(ID, notification)
            prefs.notifiedVersion = release.version
        } catch (_: SecurityException) { /* Permission may change between check and posting. */ }
    }
}
