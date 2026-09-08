package com.livetranslate.app.audio

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Mixes two 16 kHz mono PCM16 LE streams (media + mic) by averaging samples.
 *
 * Queues whole chunks (no per-byte boxing). Drops oldest samples when one side
 * lags. Mixes in block copies rather than a per-byte read so MEDIA_AND_MIC
 * capture stays cheap on the capture threads.
 */
class PcmMixer(
    private val onMixed: (ByteArray) -> Unit,
) {
    private val lock = Any()
    private val mediaQ = ArrayDeque<ByteArray>()
    private val micQ = ArrayDeque<ByteArray>()
    private var mediaOff = 0
    private var micOff = 0
    private var mediaBytes = 0
    private var micBytes = 0
    private val closed = AtomicBoolean(false)

    fun offerMedia(chunk: ByteArray) = offer(chunk, media = true)

    fun offerMic(chunk: ByteArray) = offer(chunk, media = false)

    fun close() {
        closed.set(true)
        synchronized(lock) {
            mediaQ.clear()
            micQ.clear()
            mediaOff = 0
            micOff = 0
            mediaBytes = 0
            micBytes = 0
        }
    }

    private fun offer(chunk: ByteArray, media: Boolean) {
        if (closed.get() || chunk.isEmpty()) return
        synchronized(lock) {
            if (closed.get()) return
            if (media) {
                mediaQ.addLast(chunk)
                mediaBytes += chunk.size
                trim(media = true)
            } else {
                micQ.addLast(chunk)
                micBytes += chunk.size
                trim(media = false)
            }
            drain()
        }
    }

    private fun trim(media: Boolean) {
        var bytes = if (media) mediaBytes else micBytes
        var off = if (media) mediaOff else micOff
        val q = if (media) mediaQ else micQ
        while (bytes > MAX_QUEUE_BYTES && q.isNotEmpty()) {
            val head = q.first()
            val remain = head.size - off
            if (bytes - remain >= MAX_QUEUE_BYTES) {
                q.removeFirst()
                bytes -= remain
                off = 0
            } else {
                var drop = bytes - MAX_QUEUE_BYTES
                if (drop % 2 != 0) drop++
                drop = drop.coerceAtMost(remain)
                off += drop
                bytes -= drop
                if (off >= head.size) {
                    q.removeFirst()
                    off = 0
                }
                break
            }
        }
        if (media) {
            mediaBytes = bytes
            mediaOff = off
        } else {
            micBytes = bytes
            micOff = off
        }
    }

    private fun drain() {
        dropUnalignedHeads()
        val mixBytes = (minOf(mediaBytes, micBytes) and 1.inv())
        if (mixBytes <= 0) return
        val out = ByteArray(mixBytes)
        var written = 0
        while (written < mixBytes && mediaQ.isNotEmpty() && micQ.isNotEmpty()) {
            val mediaHead = mediaQ.first()
            val micHead = micQ.first()
            val mediaRemain = mediaHead.size - mediaOff
            val micRemain = micHead.size - micOff
            val chunkBytes = min(mixBytes - written, min(mediaRemain, micRemain)) and 1.inv()
            if (chunkBytes <= 0) {
                dropUnalignedHeads()
                if (mediaQ.isEmpty() || micQ.isEmpty()) break
                if ((mediaQ.first().size - mediaOff) <= 1 ||
                    (micQ.first().size - micOff) <= 1
                ) {
                    continue
                }
                break
            }
            mixAverage(
                mediaHead,
                mediaOff,
                micHead,
                micOff,
                out,
                written,
                chunkBytes,
            )
            mediaOff += chunkBytes
            mediaBytes -= chunkBytes
            if (mediaOff >= mediaHead.size) {
                mediaQ.removeFirst()
                mediaOff = 0
            }
            micOff += chunkBytes
            micBytes -= chunkBytes
            if (micOff >= micHead.size) {
                micQ.removeFirst()
                micOff = 0
            }
            written += chunkBytes
        }
        if (written > 0 && !closed.get()) {
            onMixed(if (written == out.size) out else out.copyOf(written))
        }
    }

    /**
     * PCM16 samples are 2 bytes. A 1-byte leftover at the head of either queue
     * makes min(media, mic) odd, so [drain] would otherwise return without
     * mixing and stall even after more even-sized chunks arrive.
     */
    private fun dropUnalignedHeads() {
        dropUnalignedHead(media = true)
        dropUnalignedHead(media = false)
    }

    private fun dropUnalignedHead(media: Boolean) {
        val q = if (media) mediaQ else micQ
        var off = if (media) mediaOff else micOff
        var bytes = if (media) mediaBytes else micBytes
        while (q.isNotEmpty()) {
            val remain = q.first().size - off
            if (remain <= 0) {
                q.removeFirst()
                off = 0
                continue
            }
            if (remain == 1) {
                q.removeFirst()
                bytes -= 1
                off = 0
                continue
            }
            break
        }
        if (media) {
            mediaOff = off
            mediaBytes = bytes
        } else {
            micOff = off
            micBytes = bytes
        }
    }

    companion object {
        private const val MAX_QUEUE_BYTES = 48_000

        internal fun mixAverage(
            media: ByteArray,
            mediaOff: Int,
            mic: ByteArray,
            micOff: Int,
            out: ByteArray,
            outOff: Int,
            bytes: Int,
        ) {
            var i = 0
            while (i < bytes) {
                val mSample = sampleAt(media, mediaOff + i)
                val uSample = sampleAt(mic, micOff + i)
                val mixed = ((mSample + uSample) / 2)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[outOff + i] = (mixed and 0xFF).toByte()
                out[outOff + i + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 2
            }
        }

        private fun sampleAt(buf: ByteArray, index: Int): Int {
            val lo = buf[index].toInt() and 0xFF
            val hi = buf[index + 1].toInt()
            return ((hi shl 8) or lo).toShort().toInt()
        }
    }
}
