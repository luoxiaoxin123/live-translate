package com.livetranslate.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures microphone PCM and resamples to 16 kHz mono LE for Live Translate.
 */
class MicAudioCapturer(
    private val scope: CoroutineScope,
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(onPcm16k: (ByteArray) -> Unit) {
        stop()
        val sourceRate = 44_100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sourceRate, channelConfig, encoding)
        val bufferSize = (minBuf * 2).coerceAtLeast(sourceRate / 5 * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sourceRate,
            channelConfig,
            encoding,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("麦克风 AudioRecord 初始化失败")
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
                    if (pcm16k.isNotEmpty()) onPcm16k(pcm16k)
                } else if (n < 0) {
                    Log.w(TAG, "mic read error: $n")
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

    companion object {
        private const val TAG = "MicAudioCapturer"
    }
}
