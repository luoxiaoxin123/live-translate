package com.livetranslate.app.live

import java.io.ByteArrayOutputStream

/**
 * Holds PCM captured while no connection is ready (the planned ~10-minute
 * connection rotation, reconnect backoff) so it can be replayed after the next
 * setupComplete instead of being dropped — otherwise speech in the gap never
 * gets captioned.
 *
 * Keeps at most [maxBytes], dropping the oldest whole chunks beyond that.
 * [drain] coalesces the backlog into frames of up to [maxFrameBytes] so the
 * replay is a handful of messages rather than hundreds. Not thread-safe;
 * callers synchronize.
 */
class PendingAudioBuffer(
    private val maxBytes: Int,
    private val maxFrameBytes: Int,
) {
    private val chunks = ArrayDeque<ByteArray>()

    var sizeBytes = 0
        private set

    /** Bytes discarded since the last [clear]/[drain] because the buffer was full. */
    var droppedBytes = 0
        private set

    fun add(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        chunks.addLast(chunk)
        sizeBytes += chunk.size
        while (sizeBytes > maxBytes) {
            val oldest = chunks.removeFirst()
            sizeBytes -= oldest.size
            droppedBytes += oldest.size
        }
    }

    /** Returns the backlog oldest-first as coalesced frames and empties the buffer. */
    fun drain(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val frame = ByteArrayOutputStream(minOf(sizeBytes, maxFrameBytes))
        for (chunk in chunks) {
            if (frame.size() > 0 && frame.size() + chunk.size > maxFrameBytes) {
                frames += frame.toByteArray()
                frame.reset()
            }
            frame.write(chunk)
        }
        if (frame.size() > 0) frames += frame.toByteArray()
        clear()
        return frames
    }

    fun clear() {
        chunks.clear()
        sizeBytes = 0
        droppedBytes = 0
    }
}
