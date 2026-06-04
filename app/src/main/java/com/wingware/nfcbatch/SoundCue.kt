package com.wingware.nfcbatch

import android.media.AudioManager
import android.media.ToneGenerator

class SoundCue {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME_PERCENT)

    fun writeSuccess() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, SHORT_TONE_MS)
    }

    fun verifySuccess() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, LONG_TONE_MS)
    }

    fun error() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, LONG_TONE_MS)
    }

    fun release() {
        toneGenerator.release()
    }

    private companion object {
        const val TONE_VOLUME_PERCENT = 80
        const val SHORT_TONE_MS = 120
        const val LONG_TONE_MS = 220
    }
}
