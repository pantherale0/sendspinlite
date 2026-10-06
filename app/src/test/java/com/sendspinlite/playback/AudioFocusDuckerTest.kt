package com.sendspinlite.playback

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.sendspinlite.playback.AudioFocusDucker.Action
import org.junit.Test

class AudioFocusDuckerTest {
    @Test
    fun `may-duck loss ducks`() {
        assertThat(AudioFocusDucker.actionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
            .isEqualTo(Action.DUCK)
    }

    @Test
    fun `transient loss ducks deeper rather than pausing the group`() {
        assertThat(AudioFocusDucker.actionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
            .isEqualTo(Action.DUCK_DEEP)
    }

    @Test
    fun `regaining focus unducks`() {
        assertThat(AudioFocusDucker.actionFor(AudioManager.AUDIOFOCUS_GAIN)).isEqualTo(Action.UNDUCK)
    }

    @Test
    fun `permanent loss is treated as lost, not ducked`() {
        assertThat(AudioFocusDucker.actionFor(AudioManager.AUDIOFOCUS_LOSS)).isEqualTo(Action.LOST)
    }

    @Test
    fun `deep duck is quieter than the may-duck level`() {
        assertThat(AudioFocusDucker.DUCK_DEEP_PERCENT).isLessThan(AudioFocusDucker.DUCK_PERCENT)
    }
}
