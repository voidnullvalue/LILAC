package com.lilac.nfcbatch

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

// Cues use USAGE_ALARM so they are not silenced by ringer/notification volume.
// Android may still block audio under Do Not Disturb, hardware mute, or Bluetooth routing
// — but ringer/notification volume changes will no longer affect these cues.
class SoundCue(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Null on API < 26; we fall back to the deprecated requestAudioFocus overload there.
    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(alarmAttrs)
            .setAcceptsDelayedFocusGain(false)
            .build()
    } else null

    @Volatile private var activeTrack: AudioTrack? = null

    // 880 Hz, 120 ms — single chirp + short vibration
    fun writeSuccess() = playCue(
        segments = listOf(Segment(880.0, 120)),
        vibePattern = longArrayOf(0, 80),
    )

    // 660 Hz/100 ms → silence/60 ms → 1046 Hz/180 ms — ascending double-beep
    fun verifySuccess() = playCue(
        segments = listOf(Segment(660.0, 100), Segment(0.0, 60), Segment(1046.0, 180)),
        vibePattern = longArrayOf(0, 100, 80, 100),
    )

    // 550 Hz, 70 ms — soft neutral ping for read-only tag scan
    fun readSuccess() = playCue(
        segments = listOf(Segment(550.0, 70)),
        vibePattern = longArrayOf(0, 40),
    )

    // 220 Hz/150 ms → silence/50 ms → 180 Hz/250 ms — descending negative tone
    fun error() = playCue(
        segments = listOf(Segment(220.0, 150), Segment(0.0, 50), Segment(180.0, 250)),
        vibePattern = longArrayOf(0, 400),
    )

    fun release() {
        stopActive()
    }

    private data class Segment(val hz: Double, val ms: Int)

    private fun playCue(segments: List<Segment>, vibePattern: LongArray) {
        stopActive()
        Thread {
            val savedVol = boostAlarmVolume()
            requestFocus()
            try {
                val pcm = buildPcm(segments)
                val track = buildTrack(pcm) ?: return@Thread
                activeTrack = track
                track.play()
                Thread.sleep(segments.sumOf { it.ms }.toLong() + 30)
                track.stop()
                track.release()
                if (activeTrack === track) activeTrack = null
            } finally {
                restoreAlarmVolume(savedVol)
                abandonFocus()
            }
        }.also { it.isDaemon = true }.start()

        vibrate(vibePattern)
    }

    private fun stopActive() {
        val t = activeTrack ?: return
        activeTrack = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(alarmAttrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }.getOrNull()

    private fun buildPcm(segments: List<Segment>): ShortArray {
        val totalSamples = segments.sumOf { samplesFor(it.ms) }
        val out = ShortArray(totalSamples)
        var pos = 0
        for (seg in segments) {
            val n = samplesFor(seg.ms)
            if (seg.hz > 0.0) {
                val amp = Short.MAX_VALUE * 0.85
                val fadeLen = samplesFor(10) // 10 ms linear fade-out to prevent click artifacts
                for (i in 0 until n) {
                    val gain = if (i >= n - fadeLen) (n - i).toDouble() / fadeLen else 1.0
                    out[pos + i] = (amp * gain * sin(2.0 * PI * seg.hz * i / SAMPLE_RATE)).toInt().toShort()
                }
            }
            pos += n
        }
        return out
    }

    private fun samplesFor(ms: Int) = SAMPLE_RATE * ms / 1000

    // Temporarily raises alarm stream to max so the cue is audible even if alarm volume was low.
    // Requires MODIFY_AUDIO_SETTINGS permission; silently no-ops if unavailable.
    private fun boostAlarmVolume(): Int? = runCatching {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        if (cur < max) audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        cur
    }.getOrNull()

    private fun restoreAlarmVolume(saved: Int?) {
        saved ?: return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun vibrate(pattern: LongArray) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}
