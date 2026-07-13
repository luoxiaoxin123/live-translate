package com.livetranslate.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Captures other apps' media playback via AudioPlaybackCapture (API 29+),
 * then downsamples to 16-bit mono PCM @ 16 kHz for Live Translate.
 */
class SystemAudioCapturer(
    private val scope: CoroutineScope,
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var running = false

    fun start(
        mediaProjection: MediaProjection,
        onPcm16k: (ByteArray) -> Unit,
    ) {
        stop()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sourceRate = 44_100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sourceRate, channelConfig, encoding)
        val bufferSize = (minBuf * 2).coerceAtLeast(sourceRate / 5 * 2) // ~200ms

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sourceRate)
                    .setChannelMask(channelConfig)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord 初始化失败，无法内录系统音频")
        }

        audioRecord = record
        running = true
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val readBuf = ShortArray(bufferSize / 2)
            val resampler = PcmResampler(sourceRate, 16_000)
            while (isActive && running) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    val pcm16k = resampler.resample(readBuf, n)
                    if (pcm16k.isNotEmpty()) {
                        onPcm16k(pcm16k)
                    }
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord read error: $n")
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
    }

    /**
     * Simple linear resampler short[] @ fromRate -> ByteArray LE PCM @ toRate.
     */
    private class PcmResampler(
        private val fromRate: Int,
        private val toRate: Int,
    ) {
        private var position = 0.0
        private val step = fromRate.toDouble() / toRate.toDouble()
        private var lastSample = 0

        fun resample(input: ShortArray, length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val outCount = ((length / step).toInt() + 2).coerceAtLeast(1)
            val out = ByteArray(outCount * 2)
            var outIndex = 0
            var pos = position
            while (pos < length - 1) {
                val i = pos.toInt()
                val frac = pos - i
                val s0 = input[i].toInt()
                val s1 = input[min(i + 1, length - 1)].toInt()
                val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                if (outIndex + 1 >= out.size) break
                out[outIndex++] = (sample and 0xFF).toByte()
                out[outIndex++] = ((sample shr 8) and 0xFF).toByte()
                lastSample = sample
                pos += step
            }
            position = pos - length
            if (position < 0) position = 0.0
            return if (outIndex == out.size) out else out.copyOf(outIndex)
        }
    }

    companion object {
        private const val TAG = "SystemAudioCapturer"
    }
}
