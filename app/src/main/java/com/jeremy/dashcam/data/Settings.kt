package com.jeremy.dashcam.data

import android.content.Context
import android.content.SharedPreferences
import com.jeremy.dashcam.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Resolution(val label: String, val height: Int) { HD("720p (HD)", 720), FHD("1080p (Full HD)", 1080), UHD("2160p (4K)", 2160) }
enum class Sensitivity(val labelRes: Int) { LOW(R.string.sens_low), MEDIUM(R.string.sens_med), HIGH(R.string.sens_high) }
enum class AppLanguage(val tag: String, val labelRes: Int) { SYSTEM("", R.string.lang_system), HEBREW("he", R.string.lang_he), ENGLISH("en", R.string.lang_en) }
enum class LayoutDir(val labelRes: Int) { AUTO(R.string.dir_auto), RTL(R.string.dir_rtl), LTR(R.string.dir_ltr) }

data class AppSettings(
    val preEventSeconds: Int = 15,
    val resolution: Resolution = Resolution.FHD,
    val fps: Int = 30,
    val recordAudio: Boolean = true,
    val showDateTime: Boolean = true,
    val shockDetection: Boolean = true,
    val shockSensitivity: Sensitivity = Sensitivity.MEDIUM,
    val smartDetection: Boolean = true,
    val smartSensitivity: Sensitivity = Sensitivity.MEDIUM,
    val voiceCommands: Boolean = false,
    val voiceStartPhrase: String = "",
    val voiceStopPhrase: String = "",
    val voiceFeedback: Boolean = true,
    val autoDelete: Boolean = true,
    val maxStorageGb: Int = 8,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val layoutDir: LayoutDir = LayoutDir.AUTO,
    val overlayPromptShown: Boolean = false,
)

/** Simple, synchronous settings store observable via StateFlow (shared by UI and service). */
object SettingsStore {
    val PRE_EVENT_OPTIONS = listOf(5, 10, 15, 30, 60)
    val FPS_OPTIONS = listOf(24, 30, 60)
    val STORAGE_OPTIONS = listOf(2, 4, 8, 16, 32, 64)

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("jeremy_settings", Context.MODE_PRIVATE)
        _state.value = load(context)
    }

    private fun load(c: Context): AppSettings = with(prefs) {
        AppSettings(
            preEventSeconds = getInt("pre", 15),
            resolution = enumOr(getString("res", null), Resolution.FHD),
            fps = getInt("fps", 30),
            recordAudio = getBoolean("audio", true),
            showDateTime = getBoolean("datetime", true),
            shockDetection = getBoolean("shock", true),
            shockSensitivity = enumOr(getString("shockSens", null), Sensitivity.MEDIUM),
            smartDetection = getBoolean("smart", true),
            smartSensitivity = enumOr(getString("smartSens", null), Sensitivity.MEDIUM),
            voiceCommands = getBoolean("voice", false),
            voiceStartPhrase = getString("voiceStart", null) ?: c.getString(R.string.default_voice_start),
            voiceStopPhrase = getString("voiceStop", null) ?: c.getString(R.string.default_voice_stop),
            voiceFeedback = getBoolean("voiceFb", true),
            autoDelete = getBoolean("autoDel", true),
            maxStorageGb = getInt("maxGb", 8),
            language = enumOr(getString("lang", null), AppLanguage.SYSTEM),
            layoutDir = enumOr(getString("dir", null), LayoutDir.AUTO),
            overlayPromptShown = getBoolean("overlayPrompt", false),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        _state.value = s
        prefs.edit()
            .putInt("pre", s.preEventSeconds)
            .putString("res", s.resolution.name)
            .putInt("fps", s.fps)
            .putBoolean("audio", s.recordAudio)
            .putBoolean("datetime", s.showDateTime)
            .putBoolean("shock", s.shockDetection)
            .putString("shockSens", s.shockSensitivity.name)
            .putBoolean("smart", s.smartDetection)
            .putString("smartSens", s.smartSensitivity.name)
            .putBoolean("voice", s.voiceCommands)
            .putString("voiceStart", s.voiceStartPhrase)
            .putString("voiceStop", s.voiceStopPhrase)
            .putBoolean("voiceFb", s.voiceFeedback)
            .putBoolean("autoDel", s.autoDelete)
            .putInt("maxGb", s.maxStorageGb)
            .putString("lang", s.language.name)
            .putString("dir", s.layoutDir.name)
            .putBoolean("overlayPrompt", s.overlayPromptShown)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def
}
