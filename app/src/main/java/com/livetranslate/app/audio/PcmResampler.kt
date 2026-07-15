package com.livetranslate.app.audio

import kotlin.math.min

/**
 * Linear resampler: short[] @ fromRate -> ByteArray LE PCM16 @ toRate.
 */
class PcmResampler(
    private val fromRate: Int,
    private val toRate: Int,
) {
    private var position = 0.0
    private val step = fromRate.toDouble() / toRate.toDouble()

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
            val sample = (s0 + (s1 - s0) * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            if (outIndex + 1 >= out.size) break
            out[outIndex++] = (sample and 0xFF).toByte()
            out[outIndex++] = ((sample shr 8) and 0xFF).toByte()
            pos += step
        }
        position = pos - length
        if (position < 0) position = 0.0
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }
}
