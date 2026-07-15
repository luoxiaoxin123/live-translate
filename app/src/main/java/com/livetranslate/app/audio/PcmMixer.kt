package com.livetranslate.app.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mixes two 16 kHz mono PCM16 LE streams (media + mic) by averaging samples.
 * Soft-starts with ring-ish queues; drops lag when one side runs far ahead.
 */
class PcmMixer(
    private val onMixed: (ByteArray) -> Unit,
) {
    private val mediaQ = ConcurrentLinkedQueue<Byte>()
    private val micQ = ConcurrentLinkedQueue<Byte>()
    private val closed = AtomicBoolean(false)

    fun offerMedia(chunk: ByteArray) {
        if (closed.get()) return
        chunk.forEach { mediaQ.offer(it) }
        drain()
    }

    fun offerMic(chunk: ByteArray) {
        if (closed.get()) return
        chunk.forEach { micQ.offer(it) }
        drain()
    }

    fun close() {
        closed.set(true)
        mediaQ.clear()
        micQ.clear()
    }

    private fun drain() {
        // Keep queues from growing unbounded (~1s @ 16k mono 16bit = 32000 bytes)
        trim(mediaQ, MAX_QUEUE_BYTES)
        trim(micQ, MAX_QUEUE_BYTES)

        val frames = minOf(mediaQ.size / 2, micQ.size / 2)
        if (frames <= 0) return
        val out = ByteArray(frames * 2)
        var oi = 0
        repeat(frames) {
            val m0 = mediaQ.poll()?.toInt()?.and(0xFF) ?: return
            val m1 = mediaQ.poll()?.toInt() ?: return
            val n0 = micQ.poll()?.toInt()?.and(0xFF) ?: return
            val n1 = micQ.poll()?.toInt() ?: return
            val ms = (m1 shl 8) or m0
            val ns = (n1 shl 8) or n0
            val mSigned = ms.toShort().toInt()
            val nSigned = ns.toShort().toInt()
            val mixed = ((mSigned + nSigned) / 2)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[oi++] = (mixed and 0xFF).toByte()
            out[oi++] = ((mixed shr 8) and 0xFF).toByte()
        }
        if (oi > 0) onMixed(if (oi == out.size) out else out.copyOf(oi))
    }

    private fun trim(q: ConcurrentLinkedQueue<Byte>, max: Int) {
        while (q.size > max) {
            q.poll()
            q.poll() // drop whole sample when possible
        }
    }

    companion object {
        private const val MAX_QUEUE_BYTES = 48_000
    }
}
