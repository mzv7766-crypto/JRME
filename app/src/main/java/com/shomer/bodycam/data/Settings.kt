package com.shomer.bodycam.data

import android.content.Context
import android.content.SharedPreferences
import com.shomer.bodycam.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Resolution(val label: String, val height: Int) { HD("720p", 720), FHD("1080p", 1080), UHD("4K", 2160) }
enum class Sensitivity(val labelRes: Int) { LOW(R.string.sens_low), MEDIUM(R.string.sens_med), HIGH(R.string.sens_high) }
enum class AppLanguage(val tag: String, val labelRes: Int) { SYSTEM("", R.string.lang_system), HEBREW("he", R.string.lang_he), ENGLISH("en", R.string.lang_en) }
enum class Mounting(val labelRes: Int) { PORTRAIT(R.string.mount_portrait), LANDSCAPE(R.string.mount_landscape), AUTO(R.string.mount_auto) }

data class AppSettings(
    // video
    val preRecordSeconds: Int = 30,
    val resolution: Resolution = Resolution.FHD,
    val fps: Int = 30,
    val recordAudio: Boolean = true,
    val mounting: Mounting = Mounting.PORTRAIT,
    /** Power saving: 720p, lighter GPS and live picture (also turns on by itself when hot / low battery). */
    val powerSaver: Boolean = false,
    // what is stamped into the picture
    val operatorId: String = "",
    val stampTime: Boolean = true,
    val stampLocation: Boolean = true,
    val location: Boolean = true,
    // starting a recording
    val volumeKeys: Boolean = true,
    val shakeToRecord: Boolean = true,
    val floatingButton: Boolean = true,
    val voiceCommands: Boolean = false,
    val voiceStartPhrase: String = "",
    val voiceStopPhrase: String = "",
    // feedback
    val haptics: Boolean = true,
    val pocketOnRecord: Boolean = false,
    // safety
    val fallDetection: Boolean = true,
    val fallSensitivity: Sensitivity = Sensitivity.MEDIUM,
    val sosEnabled: Boolean = false,
    val sosName: String = "",
    val sosNumber: String = "",
    val sosSeconds: Int = 30,
    // storage
    val autoLock: Boolean = true,
    val autoDelete: Boolean = true,
    val maxStorageGb: Int = 16,
    val fullShift: Boolean = false,
    val shiftClipMinutes: Int = 5,
    val shiftMaxStorageGb: Int = 16,
    // Pro: Google Drive backup
    val driveBackup: Boolean = false,
    val driveCellular: Boolean = true,
    // app
    val language: AppLanguage = AppLanguage.SYSTEM,
    val overlayPromptShown: Boolean = false,
    val onboardingDone: Boolean = false,
)

/** Settings that a company can lock for its guards. */
enum class Locked { PRE_RECORD, AUDIO, FALL, LOCATION, AUTO_LOCK, OPERATOR }

/**
 * Synchronous settings store observable via StateFlow (shared by UI and service).
 * [state] is the EFFECTIVE configuration: what the user chose, limited to the free tier unless Pro is active,
 * and overridden by the company's policy when the phone belongs to a company.
 */
object SettingsStore {
    /** Pre-record options above this need Pro. */
    const val FREE_MAX_PRE = 30
    val PRE_RECORD_OPTIONS = listOf(10, 15, 30, 60, 120)
    val FPS_OPTIONS = listOf(24, 30, 60)
    val STORAGE_OPTIONS = listOf(4, 8, 16, 32, 64)
    val SHIFT_CLIP_OPTIONS = listOf(2, 5, 10, 15)
    val SOS_SECONDS_OPTIONS = listOf(15, 30, 45, 60)

    private lateinit var prefs: SharedPreferences
    private var user = AppSettings()
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value
    /** What the user picked (before Pro limits / company policy) – used by the settings screen. */
    val chosen: AppSettings get() = user

    private var policy: com.shomer.bodycam.cloud.Policy? = null
    private var orgStamp: String? = null
    private var pro = false
    private val _locked = MutableStateFlow<Set<Locked>>(emptySet())
    val locked: StateFlow<Set<Locked>> = _locked.asStateFlow()
    private val _pro = MutableStateFlow(false)
    /** Pro features unlocked (personal Pro, free account, or a company phone). */
    val proActive: StateFlow<Boolean> = _pro.asStateFlow()

    fun setPolicy(p: com.shomer.bodycam.cloud.Policy?, stamp: String?) { policy = p; orgStamp = stamp; recompute() }
    fun setPro(on: Boolean) { pro = on; recompute() }
    private var orgPro = false
    fun setOrgPro(on: Boolean) { if (orgPro != on) { orgPro = on; recompute() } }

    private fun recompute() {
        var s = user
        val p = policy
        val isPro = pro || orgPro || p != null
        if (!isPro) {
            s = s.copy(
                preRecordSeconds = s.preRecordSeconds.coerceAtMost(FREE_MAX_PRE),
                resolution = if (s.resolution == Resolution.UHD) Resolution.FHD else s.resolution,
                fullShift = false,
                driveBackup = false,
            )
        }
        val lock = mutableSetOf<Locked>()
        if (p != null) {
            p.preRecordSeconds?.let { s = s.copy(preRecordSeconds = it); lock += Locked.PRE_RECORD }
            p.recordAudio?.let { s = s.copy(recordAudio = it); lock += Locked.AUDIO }
            p.fallDetection?.let { s = s.copy(fallDetection = it); lock += Locked.FALL }
            s = s.copy(location = true, autoLock = p.autoLock); lock += Locked.LOCATION; lock += Locked.AUTO_LOCK
            orgStamp?.takeIf { it.isNotBlank() }?.let { s = s.copy(operatorId = it); lock += Locked.OPERATOR }
        }
        _pro.value = isPro
        _locked.value = lock
        _state.value = s
    }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("shomer_settings", Context.MODE_PRIVATE)
        user = load(context)
        recompute()
    }

    private fun load(c: Context): AppSettings = with(prefs) {
        val d = AppSettings()
        AppSettings(
            preRecordSeconds = getInt("pre", d.preRecordSeconds),
            resolution = enumOr(getString("res", null), d.resolution),
            fps = getInt("fps", d.fps),
            recordAudio = getBoolean("audio", d.recordAudio),
            mounting = enumOr(getString("mount", null), d.mounting),
            powerSaver = getBoolean("saver", d.powerSaver),
            operatorId = getString("opId", "") ?: "",
            stampTime = getBoolean("stampTime", d.stampTime),
            stampLocation = getBoolean("stampLoc", d.stampLocation),
            location = getBoolean("loc", d.location),
            volumeKeys = getBoolean("volKeys", d.volumeKeys),
            shakeToRecord = getBoolean("shake", d.shakeToRecord),
            floatingButton = getBoolean("floating", d.floatingButton),
            voiceCommands = getBoolean("voice", d.voiceCommands),
            voiceStartPhrase = getString("voiceStart", null) ?: c.getString(R.string.default_voice_start),
            voiceStopPhrase = getString("voiceStop", null) ?: c.getString(R.string.default_voice_stop),
            haptics = getBoolean("haptics", d.haptics),
            pocketOnRecord = getBoolean("pocketAuto", d.pocketOnRecord),
            fallDetection = getBoolean("fall", d.fallDetection),
            fallSensitivity = enumOr(getString("fallSens", null), d.fallSensitivity),
            sosEnabled = getBoolean("sos", d.sosEnabled),
            sosName = getString("sosName", "") ?: "",
            sosNumber = getString("sosNumber", "") ?: "",
            sosSeconds = getInt("sosSec", d.sosSeconds),
            autoLock = getBoolean("autoLock", d.autoLock),
            autoDelete = getBoolean("autoDel", d.autoDelete),
            maxStorageGb = getInt("maxGb", d.maxStorageGb),
            fullShift = getBoolean("fullShift", d.fullShift),
            shiftClipMinutes = getInt("shiftMin", d.shiftClipMinutes),
            shiftMaxStorageGb = getInt("shiftGb", d.shiftMaxStorageGb),
            driveBackup = getBoolean("drive", d.driveBackup),
            driveCellular = getBoolean("driveCell", d.driveCellular),
            language = enumOr(getString("lang", null), d.language),
            overlayPromptShown = getBoolean("overlayPrompt", false),
            onboardingDone = getBoolean("onboarding", false),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(user)
        user = s
        recompute()
        prefs.edit()
            .putInt("pre", s.preRecordSeconds)
            .putString("res", s.resolution.name)
            .putInt("fps", s.fps)
            .putBoolean("audio", s.recordAudio)
            .putString("mount", s.mounting.name)
            .putBoolean("saver", s.powerSaver)
            .putString("opId", s.operatorId)
            .putBoolean("stampTime", s.stampTime)
            .putBoolean("stampLoc", s.stampLocation)
            .putBoolean("loc", s.location)
            .putBoolean("volKeys", s.volumeKeys)
            .putBoolean("shake", s.shakeToRecord)
            .putBoolean("floating", s.floatingButton)
            .putBoolean("voice", s.voiceCommands)
            .putString("voiceStart", s.voiceStartPhrase)
            .putString("voiceStop", s.voiceStopPhrase)
            .putBoolean("haptics", s.haptics)
            .putBoolean("pocketAuto", s.pocketOnRecord)
            .putBoolean("fall", s.fallDetection)
            .putString("fallSens", s.fallSensitivity.name)
            .putBoolean("sos", s.sosEnabled)
            .putString("sosName", s.sosName)
            .putString("sosNumber", s.sosNumber)
            .putInt("sosSec", s.sosSeconds)
            .putBoolean("autoLock", s.autoLock)
            .putBoolean("autoDel", s.autoDelete)
            .putInt("maxGb", s.maxStorageGb)
            .putBoolean("fullShift", s.fullShift)
            .putInt("shiftMin", s.shiftClipMinutes)
            .putInt("shiftGb", s.shiftMaxStorageGb)
            .putBoolean("drive", s.driveBackup)
            .putBoolean("driveCell", s.driveCellular)
            .putString("lang", s.language.name)
            .putBoolean("overlayPrompt", s.overlayPromptShown)
            .putBoolean("onboarding", s.onboardingDone)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def
}
