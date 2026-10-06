package com.sendspinlite.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Holds audio focus while we're playing so that voice assistants can duck us.
 * Only when "Exclusive audio" is on (see [isEnabled]); otherwise we leave focus
 * alone, as before.
 *
 * Without focus, anything that asks for transient focus (Alexa on an Echo
 * listening or talking, a navigation prompt) never tells us about it, so the
 * music carries on at full volume over the top. And below API 26 the
 * framework never ducks on an app's behalf - the app has to do it itself.
 *
 * So on every API level we ask the framework to leave ducking to us
 * (setWillPauseWhenDucked on 26+) and turn focus changes into the service's
 * existing duck ramp. Ducking is local AudioTrack gain only - it doesn't touch
 * the protocol player volume, so the rest of a sync group carries on as is.
 *
 * A transient loss ducks rather than pauses. Pausing would stop the whole
 * group for everyone, which isn't what you want because someone spoke to one
 * speaker.
 */
class AudioFocusDucker(
    context: Context,
    private val onDuck: (duckPercent: Int) -> Unit,
    private val onUnduck: () -> Unit,
) {
    /** What to do with a given focus change. Pure, so it's easy to test. */
    enum class Action { DUCK, DUCK_DEEP, UNDUCK, LOST, IGNORE }

    companion object {
        private const val TAG = "AudioFocusDucker"
        private const val PREFS_NAME = "SendspinPlayerPrefs"

        /**
         * The "Exclusive audio" setting. Off by default: taking focus makes other
         * well-behaved music apps pause when we start, which is right on a
         * dedicated speaker but not on a phone that's also playing something else.
         */
        const val KEY_EXCLUSIVE_AUDIO = "exclusive_audio"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXCLUSIVE_AUDIO, false)

        fun setEnabled(
            context: Context,
            enabled: Boolean,
        ) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EXCLUSIVE_AUDIO, enabled)
                .apply()
        }

        /** Residual level while someone else has may-duck focus (e.g. Alexa listening). */
        const val DUCK_PERCENT = 20

        /** Residual level while someone else has exclusive transient focus (e.g. Alexa talking). */
        const val DUCK_DEEP_PERCENT = 10

        fun actionFor(focusChange: Int): Action =
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Action.DUCK
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Action.DUCK_DEEP
                AudioManager.AUDIOFOCUS_GAIN -> Action.UNDUCK
                AudioManager.AUDIOFOCUS_LOSS -> Action.LOST
                else -> Action.IGNORE
            }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var hasFocus = false

    /** True while we're ducked because of focus, as opposed to a duck intent. */
    private var focusDucked = false

    // Set when another app takes focus for good. The service calls request() on
    // every state update while playing, so without this we'd snatch focus straight
    // back. Cleared once playback stops, so the next play asks again.
    private var lostUntilStopped = false

    // One listener for both paths - the legacy API abandons focus by listener identity
    private val listener = AudioManager.OnAudioFocusChangeListener { change -> handle(change) }

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                // Deliver CAN_DUCK to us rather than ducking behind our back, so
                // every API level ducks the same way through the same ramp
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener)
                .build()
        } else {
            null
        }

    /** Ask for focus. Called when playback starts; a no-op if we already have it. */
    @Synchronized
    fun request() {
        if (hasFocus || lostUntilStopped) return
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "Audio focus requested: ${if (hasFocus) "granted" else "denied"}")
    }

    /** Give focus back. Called when playback stops and when the service goes away. */
    @Synchronized
    fun abandon() {
        lostUntilStopped = false
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
        hasFocus = false
        // Don't leave the next stream quiet if we let go mid-duck
        restoreIfFocusDucked()
        Log.i(TAG, "Audio focus abandoned")
    }

    // Focus callbacks arrive on the main thread; request/abandon come from the
    // service's state collector on a background dispatcher
    @Synchronized
    private fun handle(focusChange: Int) {
        val action = actionFor(focusChange)
        Log.i(TAG, "Focus change $focusChange -> $action")
        when (action) {
            Action.DUCK -> duck(DUCK_PERCENT)
            Action.DUCK_DEEP -> duck(DUCK_DEEP_PERCENT)
            Action.UNDUCK -> restoreIfFocusDucked()
            Action.LOST -> {
                // Someone else has taken over for good. Keep playing (the
                // server decides what happens to the stream) and ask again
                // next time playback starts.
                hasFocus = false
                lostUntilStopped = true
                restoreIfFocusDucked()
            }
            Action.IGNORE -> Unit
        }
    }

    private fun duck(percent: Int) {
        focusDucked = true
        onDuck(percent)
    }

    // Only undo a duck we caused, so a duck sent by intent (HA, Tasker) isn't
    // cancelled just because Alexa finished talking
    private fun restoreIfFocusDucked() {
        if (!focusDucked) return
        focusDucked = false
        onUnduck()
    }
}
