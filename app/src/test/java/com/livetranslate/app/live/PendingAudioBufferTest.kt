package com.livetranslate.app.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAudioBufferTest {
    private fun chunk(value: Int, size: Int) = ByteArray(size) { value.toByte() }

    @Test
    fun drainCoalescesChunksInOrder() {
        val buffer = PendingAudioBuffer(maxBytes = 100, maxFrameBytes = 10)
        buffer.add(chunk(1, 4))
        buffer.add(chunk(2, 4))
        buffer.add(chunk(3, 4))

        val frames = buffer.drain()

        assertEquals(2, frames.size)
        assertArrayEquals(chunk(1, 4) + chunk(2, 4), frames[0])
        assertArrayEquals(chunk(3, 4), frames[1])
        assertEquals(0, buffer.sizeBytes)
    }

    @Test
    fun dropsOldestChunksBeyondCapacity() {
        val buffer = PendingAudioBuffer(maxBytes = 8, maxFrameBytes = 100)
        buffer.add(chunk(1, 4))
        buffer.add(chunk(2, 4))
        buffer.add(chunk(3, 4))

        assertEquals(8, buffer.sizeBytes)
        assertEquals(4, buffer.droppedBytes)
        assertArrayEquals(chunk(2, 4) + chunk(3, 4), buffer.drain().single())
        assertEquals(0, buffer.droppedBytes)
    }

    @Test
    fun oversizedChunkBecomesItsOwnFrame() {
        val buffer = PendingAudioBuffer(maxBytes = 100, maxFrameBytes = 4)
        buffer.add(chunk(1, 2))
        buffer.add(chunk(2, 6))

        val frames = buffer.drain()

        assertEquals(listOf(2, 6), frames.map { it.size })
    }

    @Test
    fun emptyBufferDrainsToNothing() {
        val buffer = PendingAudioBuffer(maxBytes = 100, maxFrameBytes = 10)
        buffer.add(ByteArray(0))
        assertTrue(buffer.drain().isEmpty())
    }
}
