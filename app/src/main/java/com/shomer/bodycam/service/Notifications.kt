package com.shomer.bodycam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shomer.bodycam.MainActivity
import com.shomer.bodycam.R
import com.shomer.bodycam.core.ShiftState
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Recording

object Notifications {
    const val CH_SHIFT = "shomer_shift"
    const val CH_SAVED = "shomer_saved"
    const val ID_SHIFT = 1001
    private const val ID_SAVED_BASE = 2000
    private const val AMBER = 0xFFFF9F0A.toInt()
    private const val RED = 0xFFE62E2E.toInt()

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SHIFT, c.getString(R.string.ch_shift), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SAVED, c.getString(R.string.ch_saved), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) }
        )
    }

    private fun serviceIntent(c: Context, action: String, req: Int) = PendingIntent.getService(
        c, req, Intent(c, BodycamService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun openAppIntent(c: Context, route: String?, id: String? = null, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            c, req,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, route)
                .putExtra(MainActivity.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The ongoing notification of the camera foreground service, with one big action: record / stop. */
    fun shift(c: Context, s: ShiftState): Notification {
        val title = when {
            s.recording -> c.getString(R.string.n_recording)
            s.onShift -> c.getString(R.string.n_on_shift)
            else -> "Shomer"
        }
        val text = when {
            s.recording -> c.getString(R.string.n_recording_body)
            s.savingCount > 0 || s.backgroundSaving -> c.getString(R.string.n_saving)
            else -> c.getString(R.string.n_standby_body)
        }
        val b = NotificationCompat.Builder(c, CH_SHIFT)
            .setSmallIcon(R.drawable.ic_stat_shomer)
            .setColor(if (s.recording) RED else AMBER)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(c, "camera", req = 1))
            .setUsesChronometer(true)
            .setWhen(if (s.recording) s.recordStart else s.shiftStart.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
        if (s.onShift) {
            b.addAction(
                if (s.recording) R.drawable.ic_stop else R.drawable.ic_rec,
                c.getString(if (s.recording) R.string.stop_and_save else R.string.record),
                serviceIntent(c, BodycamService.ACTION_TOGGLE, 10),
            )
            b.addAction(R.drawable.ic_power, c.getString(R.string.end_shift), serviceIntent(c, BodycamService.ACTION_STOP, 12))
        }
        return b.build()
    }

    fun saved(c: Context, r: Recording) {
        val thumb = RecordRepository.thumbFile(r.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val n = NotificationCompat.Builder(c, CH_SAVED)
            .setSmallIcon(R.drawable.ic_stat_shomer)
            .setColor(AMBER)
            .setContentTitle(c.getString(R.string.n_saved))
            .setContentText(c.getString(R.string.n_saved_body, formatDuration(r.durationMs)))
            .setLargeIcon(thumb)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c, "record", r.id, req = (r.triggerTime % 100000).toInt() + 100))
            .build()
        notify(c, ID_SAVED_BASE + (r.triggerTime % 1000).toInt(), n)
    }

    fun failed(c: Context) {
        val n = NotificationCompat.Builder(c, CH_SAVED)
            .setSmallIcon(R.drawable.ic_stat_shomer)
            .setColor(RED)
            .setContentTitle(c.getString(R.string.n_failed))
            .setAutoCancel(true)
            .build()
        notify(c, ID_SAVED_BASE - 1, n)
    }

    private fun notify(c: Context, id: Int, n: Notification) {
        if (NotificationManagerCompat.from(c).areNotificationsEnabled()) {
            try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) { }
        }
    }

    fun formatDuration(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
