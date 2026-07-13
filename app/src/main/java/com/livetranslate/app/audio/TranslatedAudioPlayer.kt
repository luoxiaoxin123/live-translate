package com.livetranslate.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Plays translated PCM from Live Translate on a dedicated writer thread.
 *
 * Critical design points:
 * - Never write AudioTrack on the main/UI thread (blocking write freezes the app).
 * - Mark output as not capturable so MediaProjection system-audio capture cannot
 *   pick up the translation and create a feedback loop (same sentence repeating).
 * - Drop excess queued audio when lagging instead of growing forever / crashing.
 */
class TranslatedAudioPlayer {
    private val enabled = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile
    private var volume = 0.8f

    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_CHUNKS)
    private val trackRef = AtomicReference<AudioTrack?>(null)
    private var sampleRate = DEFAULT_SAMPLE_RATE

    private val writer = thread(
        name = "translated-audio-writer",
        isDaemon = true,
        start = true,
    ) {
        writeLoop()
    }

    fun setEnabled(value: Boolean) {
        val was = enabled.getAndSet(value)
        if (!value && was) {
            queue.clear()
            runCatching {
                trackRef.get()?.pause()
                trackRef.get()?.flush()
            }
        } else if (value && !was) {
            runCatching { trackRef.get()?.play() }
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        runCatching { trackRef.get()?.setVolume(volume) }
    }

    /**
     * Enqueue a PCM chunk. Non-blocking for callers.
     * If the queue is full, drop the oldest chunk (prefer low latency over backlog).
     */
    fun playPcm(chunk: ByteArray, mimeType: String?) {
        if (!enabled.get() || released.get() || chunk.isEmpty()) return
        parseSampleRate(mimeType)?.let { rate ->
            if (rate != sampleRate && rate in 8_000..48_000) {
                sampleRate = rate
                // Recreate track on writer thread via sentinel empty + ensure
                queue.offer(RECREATE_SENTINEL)
            }
        }
        // Drop oldest if full to avoid unbounded memory / multi-second lag
        while (!queue.offer(chunk.copyOf())) {
            queue.poll()
            if (released.get()) return
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        enabled.set(false)
        queue.clear()
        queue.offer(STOP_SENTINEL)
        runCatching { writer.join(500) }
        releaseTrack()
    }

    private fun writeLoop() {
        while (!released.get()) {
            val chunk = try {
                queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            if (chunk === STOP_SENTINEL) break
            if (chunk === RECREATE_SENTINEL) {
                releaseTrack()
                continue
            }
            if (!enabled.get()) continue
            try {
                val track = ensureTrack(sampleRate) ?: continue
                var offset = 0
                while (offset < chunk.size && enabled.get() && !released.get()) {
                    val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    } else {
                        track.write(chunk, offset, chunk.size - offset)
                    }
                    when {
                        written > 0 -> offset += written
                        written == 0 -> {
                            // Buffer full — brief yield then retry a few times, then drop rest
                            Thread.sleep(5)
                            // If still can't write, drop remaining of this chunk
                            val retry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            } else {
                                0
                            }
                            if (retry <= 0) break
                            offset += retry
                        }
                        else -> {
                            // ERROR_INVALID_OPERATION / DEAD_OBJECT etc.
                            Log.w(TAG, "AudioTrack.write error=$written, recreating")
                            releaseTrack()
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "writeLoop error", t)
                releaseTrack()
            }
        }
        releaseTrack()
    }

    private fun ensureTrack(rate: Int): AudioTrack? {
        val existing = trackRef.get()
        if (existing != null && existing.sampleRate == rate &&
            existing.playState != AudioTrack.PLAYSTATE_STOPPED
        ) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING && enabled.get()) {
                runCatching { existing.play() }
            }
            return existing
        }
        releaseTrack()
        if (rate <= 0) return null

        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "invalid min buffer for rate=$rate: $minBuf")
            return null
        }
        // ~300ms buffer — enough to avoid underruns without huge latency
        val bufferSize = (minBuf * 3).coerceAtLeast(rate / 3 * 2)

        val attrs = AudioAttributes.Builder()
            // Not USAGE_MEDIA: system audio capture matches USAGE_MEDIA and would
            // re-ingest translation → infinite "same sentence" loop.
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                }
            }
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack create failed", t)
            return null
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized")
            runCatching { track.release() }
            return null
        }

        runCatching {
            track.setVolume(volume)
            track.play()
        }.onFailure {
            Log.e(TAG, "AudioTrack play failed", it)
            runCatching { track.release() }
            return null
        }

        trackRef.set(track)
        sampleRate = rate
        Log.i(TAG, "AudioTrack ready rate=$rate buf=$bufferSize")
        return track
    }

    private fun releaseTrack() {
        val t = trackRef.getAndSet(null) ?: return
        runCatching {
            t.pause()
            t.flush()
            t.release()
        }
    }

    private fun parseSampleRate(mimeType: String?): Int? {
        if (mimeType.isNullOrBlank()) return null
        val match = Regex("rate=(\\d+)").find(mimeType) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    companion object {
        private const val TAG = "TranslatedAudioPlayer"
        private const val DEFAULT_SAMPLE_RATE = 24_000
        private const val MAX_QUEUE_CHUNKS = 32
        private val STOP_SENTINEL = ByteArray(0)
        private val RECREATE_SENTINEL = ByteArray(1)
    }
}
