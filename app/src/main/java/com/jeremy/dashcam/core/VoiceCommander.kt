package com.jeremy.dashcam.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Continuous on-device voice command listener + spoken feedback.
 * Must be used from the main thread (SpeechRecognizer requirement).
 */
class VoiceCommander(private val context: Context, private val onStart: () -> Unit, private val onStop: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var startPhrase = ""
    private var stopPhrase = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastCommandAt = 0L

    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                val loc = Locale.getDefault()
                if (tts?.isLanguageAvailable(loc) ?: -1 >= TextToSpeech.LANG_AVAILABLE) tts?.language = loc
            }
        }
    }

    fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jeremy")
    }

    fun start(startPhrase: String, stopPhrase: String) {
        this.startPhrase = normalize(startPhrase)
        this.stopPhrase = normalize(stopPhrase)
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { Log.w(TAG, "no recognizer"); return }
        listening = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        listenNow()
    }

    fun stop() {
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.run { runCatching { cancel() }; runCatching { destroy() } }
        recognizer = null
    }

    fun release() {
        stop()
        tts?.shutdown(); tts = null; ttsReady = false
    }

    private fun listenNow() {
        if (!listening) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        runCatching { recognizer?.startListening(i) }.onFailure { restart(2000) }
    }

    private fun restart(delay: Long) {
        if (!listening) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ runCatching { recognizer?.cancel() }; listenNow() }, delay)
    }

    private fun handle(results: Bundle?): Boolean {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        if (android.os.SystemClock.elapsedRealtime() - lastCommandAt < 3000) return false
        for (raw in list) {
            val t = normalize(raw)
            if (stopPhrase.isNotEmpty() && t.contains(stopPhrase)) { lastCommandAt = android.os.SystemClock.elapsedRealtime(); onStop(); return true }
            if (startPhrase.isNotEmpty() && t.contains(startPhrase)) { lastCommandAt = android.os.SystemClock.elapsedRealtime(); onStart(); return true }
        }
        return false
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) { handle(results); restart(300) }
        override fun onPartialResults(partialResults: Bundle?) {
            if (handle(partialResults)) restart(1500)
        }
        override fun onError(error: Int) {
            restart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_AUDIO) 3000 else 500)
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "VoiceCommander"
        fun normalize(s: String) = s.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
