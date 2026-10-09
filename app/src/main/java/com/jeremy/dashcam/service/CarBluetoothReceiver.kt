package com.jeremy.dashcam.service

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jeremy.dashcam.MainActivity
import com.jeremy.dashcam.R
import com.jeremy.dashcam.core.DashcamController
import com.jeremy.dashcam.data.ProStore
import com.jeremy.dashcam.data.SettingsStore

/**
 * PRO: starts drive mode when the phone connects to the chosen car Bluetooth, and ends it on disconnect.
 * Starting the camera needs the app in the foreground, so Jeremy briefly opens (allowed thanks to the
 * "display over other apps" permission) and immediately goes back to the background.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SettingsStore.init(context); ProStore.init(context)
        val s = SettingsStore.current
        if (!ProStore.state.value.isPro || s.carBtAddress.isNullOrEmpty()) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        if (device?.address != s.carBtAddress) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i("Jeremy", "car bluetooth connected → auto start")
                if (DashcamController.state.value.driveActive) return
                val open = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTO_START, true)
                if (Settings.canDrawOverlays(context)) {
                    runCatching { context.startActivity(open) }.onFailure { notifyTapToStart(context, open) }
                } else notifyTapToStart(context, open)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i("Jeremy", "car bluetooth disconnected → stop drive")
                if (DashcamController.state.value.driveActive) DashcamController.stopDrive(context)
            }
        }
    }

    private fun notifyTapToStart(c: Context, open: Intent) {
        val pi = PendingIntent.getActivity(c, 77, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, Notifications.CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_jeremy)
            .setContentTitle("הרכב התחבר")
            .setContentText("הקש כדי להפעיל את Jeremy")
            .setContentIntent(pi).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        Notifications.notify(c, 3001, n)
    }
}
