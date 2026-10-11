package com.shomer.bodycam.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.shomer.bodycam.data.SettingsStore

/**
 * The camera is worn on the body, so the wearer can't look at the screen: every state change is
 * confirmed by a distinct vibration pattern instead.
 *   started  ▬▬▬        one long
 *   stopped  ▪ ▪        two short
 *   saved    ▪ ▪ ▪      three short
 *   shift on ▪ ▬        short + long
 *   alarm    ▬ ▬ ▬      three long (man-down)
 */
object Haptics {
    enum class Pattern(val timings: LongArray) {
        STARTED(longArrayOf(0, 450)),
        STOPPED(longArrayOf(0, 90, 110, 90)),
        SAVED(longArrayOf(0, 60, 90, 60, 90, 60)),
        SHIFT_ON(longArrayOf(0, 80, 120, 320)),
        SHIFT_OFF(longArrayOf(0, 320, 120, 80)),
        ALARM(longArrayOf(0, 600, 250, 600, 250, 600)),
    }

    fun play(c: Context, p: Pattern, force: Boolean = false) {
        if (!force && !SettingsStore.current.haptics) return
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (v == null || !v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(p.timings, -1)) }
    }
}
