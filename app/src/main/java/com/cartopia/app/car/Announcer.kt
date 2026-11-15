package com.cartopia.app.car

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts

/**
 * Short voice prompts. Uses the Pleos TextToSpeech SDK on the car and Android TTS elsewhere.
 *
 * The driver is usually following the navigation app, so a prompt never talks over its guidance: while navigation
 * is speaking the prompt waits, and if it is still speaking after [maxWaitMs] the prompt is dropped. Only the newest
 * waiting prompt is kept. Prompts play as notifications, so the car's audio policy mixes them under media.
 */
class Announcer(context: Context) {

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var waiting: Runnable? = null
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
                    runCatching { tts.setAudioAttributes(attributes) }
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

    /** Speaks [text] once navigation guidance is quiet; gives up after [maxWaitMs]. Safe to call from any thread. */
    fun say(text: String, maxWaitMs: Long = 15_000) {
        main.post {
            waiting?.let(main::removeCallbacks)
            val giveUpAt = System.currentTimeMillis() + maxWaitMs
            val attempt = object : Runnable {
                override fun run() {
                    if (navigationSpeaking()) {
                        if (System.currentTimeMillis() < giveUpAt) main.postDelayed(this, RETRY_MS) else waiting = null
                        return
                    }
                    waiting = null
                    speakNow(text)
                }
            }
            waiting = attempt
            attempt.run()
        }
    }

    private fun speakNow(text: String) {
        if (!ready) return
        pleos?.let { runCatching { it.speak(text, false) }; return }
        android?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    /** Whether any app is playing turn-by-turn guidance right now. */
    private fun navigationSpeaking(): Boolean = runCatching {
        audio.activePlaybackConfigurations.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
    }.getOrDefault(false)

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
                android?.setAudioAttributes(attributes)
                ready = true
            }
        }
    }

    private companion object {
        const val TAG = "Announcer"
        const val RETRY_MS = 500L
    }
}
