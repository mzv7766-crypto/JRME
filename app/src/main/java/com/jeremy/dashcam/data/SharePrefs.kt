package com.jeremy.dashcam.data

import android.content.Context

/**
 * What is sent as a message next to a shared video. Set once in Settings – sharing itself never asks.
 * Defaults: date/time and reason only (location etc. are opt-in for privacy).
 */
object SharePrefs {
    data class Option(val key: String, val label: String, val default: Boolean)

    val options = listOf(
        Option("time", "תאריך ושעה", true),
        Option("reason", "סיבת האירוע", true),
        Option("map", "מיקום (קישור למפה)", false),
        Option("speed", "מהירות", false),
        Option("plates", "לוחיות רישוי", false),
        Option("ai", "מה זוהה (AI)", false),
        Option("name", "שם האירוע", true),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("jeremy_share2", Context.MODE_PRIVATE)
    fun isOn(c: Context, key: String) = prefs(c).getBoolean(key, options.first { it.key == key }.default)
    fun set(c: Context, key: String, on: Boolean) = prefs(c).edit().putBoolean(key, on).apply()
    fun summary(c: Context): String {
        val on = options.filter { isOn(c, it.key) }.map { it.label }
        return when {
            on.isEmpty() -> "רק הסרטון"
            on.size <= 3 -> on.joinToString(", ")
            else -> on.take(2).joinToString(", ") + " ועוד ${on.size - 2}"
        }
    }
}
