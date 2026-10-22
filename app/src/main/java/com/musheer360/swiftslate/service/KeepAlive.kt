package com.musheer360.swiftslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import com.musheer360.swiftslate.MainActivity
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.SwiftSlateApp

/**
 * The survival armor's visible half, plus the one shared "is my service actually alive" check.
 *
 * MIUI/Realme/Vivo kill accessibility services silently in the background. The user keeps
 * typing `?fix` into a dead app, decides the app is broken, and uninstalls — the death was
 * never visible from outside. This object publishes the service's true state as a
 * persistent shade notification (active / stopped), so a dead service is impossible to
 * miss, and exposes [isServiceAlive] for the tile and dashboard to read.
 *
 * Everything is best-effort: notification posting throws SecurityException on Android 13+
 * when the runtime permission was denied — the stack trace would be noise, the outcome is
 * the same (no notification) either way.
 */
object KeepAlive {

    private const val CHANNEL_ID = "ghostwriter_status"
    private const val NOTIFICATION_ID = 2001

    /**
     * True only when the accessibility service is BOTH enabled AND not stuck in the
     * framework's "crashed services" limbo. Shared by the Quick Settings tile and the
     * dashboard; the dashboard additionally ORs in its crash-marker history check —
     * a service that crashed while the app was closed reads as enabled here.
     */
    fun isServiceAlive(context: Context): Boolean =
        checkServiceEnabled(context) && !isServiceCrashed(context)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.keepalive_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        try {
            manager.createNotificationChannel(channel)
        } catch (_: Exception) {
        }
    }

    /** Service connected and healthy: quiet, permanent "everything is fine" notice. */
    fun postActive(context: Context) {
        ensureChannel(context)
        val notification = build(context, stopped = false)
        notify(context, notification)
    }

    /** Service died (or was switched off): the one state that must be loud about itself. */
    fun postStopped(context: Context) {
        ensureChannel(context)
        val notification = build(context, stopped = true)
        notify(context, notification)
    }

    fun clear(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun build(context: Context, stopped: Boolean): Notification {
        val target = if (stopped) {
            // No intent into our own class for the stopped case: the only place a dead
            // service can be restarted is the accessibility toggles screen.
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            Intent(context, MainActivity::class.java)
        }

        val pending = PendingIntent.getActivity(
            context,
            if (stopped) 1002 else 1001,
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(
                if (stopped) context.getString(R.string.keepalive_stopped_title)
                else context.getString(R.string.keepalive_active_title, appLabel)
            )
            .setContentText(
                if (stopped) context.getString(R.string.keepalive_stopped_text)
                else context.getString(R.string.keepalive_active_text)
            )
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun notify(context: Context, notification: Notification) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // No POST_NOTIFICATIONS grant (13+): the dashboard card and the tile still tell
            // the truth — this surface just stays silent.
        }
    }

    private fun checkServiceEnabled(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Same hidden-flag read the dashboard does (private copy in ui/DashboardScreen.kt);
     * duplicated rather than cross-imported from the UI layer, which the service must
     * never reach into. A service stuck in the limbo still reports as "enabled" — without
     * this read the tile would lie exactly when the app is dying (#125 lineage).
     */
    @Suppress("SoonBlockedPrivateApi")
    private fun isServiceCrashed(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val field = android.accessibilityservice.AccessibilityServiceInfo::class.java
                .getDeclaredField("crashed")
            am.getInstalledAccessibilityServiceList().any {
                try {
                    it.resolveInfo.serviceInfo.packageName == context.packageName && field.getBoolean(it)
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
