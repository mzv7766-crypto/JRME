package com.jeremy.dashcam.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.jeremy.dashcam.data.SettingsStore

/**
 * Volume-key control while drive mode is on:
 *   short press VOLUME UP   → start event / stop & SAVE event
 *   short press VOLUME DOWN → end the running event WITHOUT saving (if no event: normal volume down)
 *   press & hold (either)   → normal volume change, so music volume still works.
 */
object VolumeKeyHandler {
    private const val HOLD_MS = 450L
    private const val REPEAT_MS = 120L
    private val main = Handler(Looper.getMainLooper())
    private var handlingKey = 0
    private var holding = false
    private var repeat: Runnable? = null

    fun isActive(): Boolean {
        val svc = DashcamService.instance ?: return false
        return SettingsStore.current.volumeKeys && svc.isDriving()
    }

    /** @return true when the key is consumed. */
    fun onKey(context: Context, e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val up = code == KeyEvent.KEYCODE_VOLUME_UP
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return handlingKey == code
                if (!isActive()) { handlingKey = 0; return false }
                // Volume-down without a running event keeps its normal meaning.
                if (!up && !DashcamService.instance!!.isEventActive()) { handlingKey = 0; return false }
                handlingKey = code
                holding = false
                val r = object : Runnable {
                    override fun run() {
                        holding = true
                        adjust(context, up)
                        main.postDelayed(this, REPEAT_MS)
                    }
                }
                repeat = r
                main.postDelayed(r, HOLD_MS)
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (handlingKey != code) return false
                repeat?.let { main.removeCallbacks(it) }; repeat = null
                handlingKey = 0
                if (!holding) DashcamService.instance?.onVolumeAction(up)
                holding = false
                return true
            }
        }
        return false
    }

    private fun adjust(c: Context, up: Boolean) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustSuggestedStreamVolume(
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
        )
    }

    fun isAccessibilityEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(c, VolumeKeyService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

/** Receives volume keys system-wide (also with Waze/Spotify in front). Does nothing else. */
class VolumeKeyService : AccessibilityService() {
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = VolumeKeyHandler.onKey(this, event)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
