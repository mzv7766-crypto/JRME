package com.jeremy.dashcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jeremy.dashcam.MainActivity
import com.jeremy.dashcam.R
import com.jeremy.dashcam.core.DashcamState
import com.jeremy.dashcam.data.EventRecord
import com.jeremy.dashcam.data.EventRepository

object Notifications {
    const val CH_DRIVE = "jeremy_drive"
    const val CH_EVENTS = "jeremy_events"
    const val ID_DRIVE = 1001
    private const val ID_EVENT_BASE = 2000
    private const val COLOR = 0xFF22C55E.toInt()

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_DRIVE, c.getString(R.string.ch_drive), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, c.getString(R.string.ch_events), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun serviceIntent(c: Context, action: String, req: Int) = PendingIntent.getService(
        c, req, Intent(c, DashcamService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun openAppIntent(c: Context, route: String?, eventId: String? = null, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            c, req,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, route)
                .putExtra(MainActivity.EXTRA_EVENT_ID, eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The ongoing notification required for the camera foreground service, with quick actions. */
    fun drive(c: Context, s: DashcamState): Notification {
        val title = c.getString(R.string.drive_active)
        val text = when {
            s.eventActive -> c.getString(R.string.n_event_running)
            s.savingCount > 0 -> c.getString(R.string.n_saving)
            else -> c.getString(R.string.camera_running_bg)
        }
        val b = NotificationCompat.Builder(c, CH_DRIVE)
            .setSmallIcon(R.drawable.ic_stat_jeremy)
            .setColor(COLOR)
            .setColorized(false)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(c, "camera", req = 1))
            .setUsesChronometer(true)
            .setWhen(if (s.eventActive) s.eventStartTime else s.driveStartTime.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
        b.addAction(
            if (s.eventActive) R.drawable.ic_stop else R.drawable.ic_warning,
            c.getString(if (s.eventActive) R.string.stop_event else R.string.save_event),
            serviceIntent(c, DashcamService.ACTION_TOGGLE_EVENT, 10),
        )
        b.addAction(R.drawable.ic_videocam, c.getString(R.string.show_camera), openAppIntent(c, "camera", req = 11))
        b.addAction(R.drawable.ic_power, c.getString(R.string.stop_camera), serviceIntent(c, DashcamService.ACTION_STOP, 12))
        return b.build()
    }

    fun eventSaved(c: Context, r: EventRecord) {
        val thumb = EventRepository.thumbFile(r.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val n = NotificationCompat.Builder(c, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_jeremy)
            .setColor(COLOR)
            .setContentTitle(c.getString(R.string.n_saved))
            .setContentText(c.getString(R.string.n_saved_body, formatDuration(r.durationMs)))
            .setLargeIcon(thumb)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c, "event", r.id, req = (r.triggerTime % 100000).toInt() + 100))
            .build()
        notify(c, ID_EVENT_BASE + (r.triggerTime % 1000).toInt(), n)
    }

    fun eventFailed(c: Context) {
        val n = NotificationCompat.Builder(c, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_jeremy)
            .setColor(0xFFE53935.toInt())
            .setContentTitle(c.getString(R.string.n_failed))
            .setAutoCancel(true)
            .build()
        notify(c, ID_EVENT_BASE - 1, n)
    }

    fun notify(c: Context, id: Int, n: Notification) {
        if (NotificationManagerCompat.from(c).areNotificationsEnabled()) {
            try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) { }
        }
    }

    fun formatDuration(ms: Long): String {
        val t = ms / 1000
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
