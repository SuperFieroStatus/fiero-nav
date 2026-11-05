package dev.mike.fieronav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.mike.fieronav.ui.KeyRouter
import java.util.Locale

/**
 * Spoken prompts through the phone's TTS engine (Pico on the Flip 2). Ducks music (Spotify) while
 * speaking. A new prompt replaces one still being spoken: the latest instruction is the one that matters.
 */
class Voice(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var ready = false
    private var pending: String? = null
    private var id = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(attrs)
            tts.language = Locale.US
            tts.setSpeechRate(1.05f)
            pending?.let { say(it, true) }
        } else {
            Log.w(KeyRouter.TAG, "TTS init failed ($status)")
        }
        pending = null
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (!isSpeaking) audio.abandonAudioFocusRequest(focus)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audio.abandonAudioFocusRequest(focus)
            }
        })
    }

    fun say(text: String, force: Boolean) {
        if (!ready) {
            pending = text
            return
        }
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "p${id++}")
    }

    /** Call audio in progress (cellular or VoIP), whatever the telephony state says. */
    fun callAudioActive(): Boolean =
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE

    fun stop() {
        if (ready) tts.stop()
        audio.abandonAudioFocusRequest(focus)
    }
}
