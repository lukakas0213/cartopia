package com.roadcanvas.app.car

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts

/** Short voice prompts. Uses the Pleos TextToSpeech SDK on the car and Android TTS elsewhere. */
class Announcer(context: Context) {

    private val app = context.applicationContext
    private var pleos: PleosTts? = null
    private var android: TextToSpeech? = null
    private var ready = false

    fun start() {
        val tts = runCatching { PleosTts(app, Mode.ON_DEVICE) }.getOrNull()
        if (tts == null) {
            useAndroidTts()
            return
        }
        pleos = tts
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    tts.setLocale(Locale.KOREAN)
                    ready = true
                }

                override fun onFailure(error: ErrorType) {
                    Log.w(TAG, "Pleos TTS init failed: $error")
                    pleos = null
                    useAndroidTts()
                }
            })
        }.onFailure {
            pleos = null
            useAndroidTts()
        }
    }

    fun say(text: String) {
        if (!ready) return
        pleos?.let { runCatching { it.speak(text, false) }; return }
        android?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun stop() {
        runCatching { pleos?.release() }
        android?.shutdown()
        pleos = null
        android = null
        ready = false
    }

    private fun useAndroidTts() {
        if (android != null) return
        android = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                android?.language = Locale.KOREAN
                ready = true
            }
        }
    }

    private companion object {
        const val TAG = "Announcer"
    }
}
