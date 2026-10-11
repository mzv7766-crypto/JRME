package com.shomer.bodycam.service

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shomer.bodycam.data.SettingsStore

/**
 * Volume-key control while a shift is on and Shomer is open (also in pocket mode with the black screen):
 *   short press VOLUME UP → start recording / stop & SAVE the recording
 *   press & hold          → normal volume change.
 * VOLUME DOWN is never used: a body camera must not throw a recording away by accident.
 */
object VolumeKeyHandler {
    private const val HOLD_MS = 450L
    private const val REPEAT_MS = 120L
    private val main = Handler(Looper.getMainLooper())
    private var handlingKey = 0
    private var holding = false
    private var repeat: Runnable? = null

    fun isActive(): Boolean {
        val svc = BodycamService.instance ?: return false
        return SettingsStore.current.volumeKeys && svc.isOnShift()
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
                if (!up) { handlingKey = 0; return false }
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
                if (!holding) BodycamService.instance?.onVolumeAction(up)
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

}
