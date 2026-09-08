package com.livetranslate.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMixerTest {

    @Test
    fun mixAverageTakesTheMean() {
        val media = pcm(1000, -1000, 20_000)
        val mic = pcm(3000, 1000, -4_000)
        val out = ByteArray(media.size)
        PcmMixer.mixAverage(media, 0, mic, 0, out, 0, media.size)
        assertEquals(listOf(2000, 0, 8_000), samples(out))
    }

    @Test
    fun mixAverageDoesNotOverflowAtTheShortExtremes() {
        val media = pcm(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        val mic = pcm(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        val out = ByteArray(media.size)
        PcmMixer.mixAverage(media, 0, mic, 0, out, 0, media.size)
        assertEquals(
            listOf(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()),
            samples(out),
        )
    }

    @Test
    fun alignedChunksAverageAndFlush() {
        val mixed = mutableListOf<ByteArray>()
        val mixer = PcmMixer { mixed += it }
        mixer.offerMedia(pcm(10, 20, 30, 40))
        assertTrue(mixed.isEmpty())
        mixer.offerMic(pcm(0, 0, 10, 20))
        assertEquals(1, mixed.size)
        assertEquals(listOf(5, 10, 20, 30), samples(mixed.single()))
        mixer.close()
    }

    @Test
    fun unalignedChunksMixAcrossBoundaries() {
        val mixed = mutableListOf<ByteArray>()
        val mixer = PcmMixer { mixed += it }
        mixer.offerMedia(pcm(100, 200, 300))
        mixer.offerMic(pcm(0))
        mixer.offerMic(pcm(20, 40))
        assertEquals(listOf(50, 110, 170), mixed.flatMap { samples(it) })
        mixer.close()
    }

    @Test
    fun trailingOddByteDoesNotStallTheMixer() {
        val mixed = mutableListOf<ByteArray>()
        val mixer = PcmMixer { mixed += it }
        mixer.offerMedia(byteArrayOf(10, 0, 99))
        mixer.offerMic(pcm(10, 30))
        assertEquals(listOf(10), mixed.flatMap { samples(it) })
        mixer.close()
    }

    @Test
    fun leftoverOddBytesOnBothSidesDoNotBlockLaterChunks() {
        val mixed = mutableListOf<ByteArray>()
        val mixer = PcmMixer { mixed += it }
        mixer.offerMedia(byteArrayOf(1))
        mixer.offerMic(byteArrayOf(2))
        assertTrue(mixed.isEmpty())
        mixer.offerMedia(pcm(20, 40))
        mixer.offerMic(pcm(10, 20))
        assertEquals(listOf(15, 30), mixed.flatMap { samples(it) })
        mixer.close()
    }

    @Test
    fun closeDropsFurtherChunks() {
        val mixed = mutableListOf<ByteArray>()
        val mixer = PcmMixer { mixed += it }
        mixer.close()
        mixer.offerMedia(pcm(1, 2))
        mixer.offerMic(pcm(1, 2))
        assertTrue(mixed.isEmpty())
    }

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, raw ->
            val sample = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(buf: ByteArray): List<Int> {
        val out = ArrayList<Int>(buf.size / 2)
        var i = 0
        while (i + 1 < buf.size) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            out += ((hi shl 8) or lo).toShort().toInt()
            i += 2
        }
        return out
    }
}
