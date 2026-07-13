package com.livetranslate.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays translated PCM audio returned by Live Translate.
 * Does NOT duck or pause other apps' media — plays in parallel.
 *
 * Default assumed format: 16-bit LE mono PCM @ 24 kHz (common for Gemini audio out).
 * Mime type from the server can override the sample rate when present
 * (e.g. audio/pcm;rate=24000).
 */
class TranslatedAudioPlayer {
    private var track: AudioTrack? = null
    private var sampleRate = 24_000
    private val enabled = AtomicBoolean(false)
    @Volatile
    private var volume = 0.8f

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) {
            // Keep track but don't write; optional flush
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        track?.setVolume(volume)
    }

    fun playPcm(chunk: ByteArray, mimeType: String?) {
        if (!enabled.get() || chunk.isEmpty()) return
        val rate = parseSampleRate(mimeType) ?: sampleRate
        ensureTrack(rate)
        val t = track ?: return
        try {
            t.write(chunk, 0, chunk.size)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack write failed: ${e.message}")
        }
    }

    fun release() {
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun ensureTrack(rate: Int) {
        if (track != null && sampleRate == rate) return
        release()
        sampleRate = rate
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = (minBuf * 2).coerceAtLeast(rate / 5 * 2)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume)
        t.play()
        track = t
    }

    private fun parseSampleRate(mimeType: String?): Int? {
        if (mimeType.isNullOrBlank()) return null
        val match = Regex("rate=(\\d+)").find(mimeType) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    companion object {
        private const val TAG = "TranslatedAudioPlayer"
    }
}
