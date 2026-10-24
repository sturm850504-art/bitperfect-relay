package com.bitperfect.relay.output.usb

import kotlin.math.round

object PcmPacker {

    /** Q16.16 unity gain: the packer then leaves every sample exactly as converted. */
    const val UNITY_GAIN = 65536

    /**
     * Float to little-endian integer PCM in [subslotBytes]-byte slots, [bitResolution] bits left-justified.
     * [gainQ16]: digital volume (user option, not bit-perfect, task 2026-10-24) applied to the integer
     * sample as (x * gain + 0.5) >> 16. At [UNITY_GAIN] it is skipped, so the output is bit-exact.
     */
    fun pack(
        src: FloatArray, srcOffset: Int, sampleCount: Int,
        subslotBytes: Int, bitResolution: Int,
        dst: ByteArray, dstOffset: Int,
        gainQ16: Int = UNITY_GAIN,
    ): Int {
        // Validation
        if (subslotBytes !in 2..4) throw IllegalArgumentException("subslotBytes must be in 2..4")
        if (bitResolution !in 1..(subslotBytes * 8)) throw IllegalArgumentException("bitResolution must be in 1..${subslotBytes * 8}")
        if (sampleCount < 0) throw IllegalArgumentException("sampleCount must be >= 0")
        if (srcOffset < 0 || srcOffset + sampleCount > src.size) throw IllegalArgumentException("source range out of bounds")
        if (dstOffset < 0 || dstOffset + sampleCount * subslotBytes > dst.size) throw IllegalArgumentException("destination range out of bounds")
        if (gainQ16 !in 0..UNITY_GAIN) throw IllegalArgumentException("gainQ16 must be in 0..$UNITY_GAIN")
        val scaled = gainQ16 != UNITY_GAIN

        val full: Long = 1L shl (bitResolution - 1)
        val shift = subslotBytes * 8 - bitResolution

        for (i in 0 until sampleCount) {
            // Step 1: NaN -> 0
            val x: Double = if (src[srcOffset + i].isNaN()) 0.0 else src[srcOffset + i].toDouble()
            // Step 2: scale
            val y = x * full
            // Step 3: round (ties to even)
            val rounded: Long = round(y).toLong()
            // Step 4: clamp to [-full, full-1]
            var clamped: Long = if (rounded < -full) -full else if (rounded > full - 1) full - 1 else rounded
            // Step 4b: digital volume (integer gain, round half up); never above unity, so no clamp needed.
            if (scaled) clamped = (clamped * gainQ16 + 0x8000L) shr 16
            // Step 5: shift into top bits
            val result: Long = clamped shl shift
            // Step 6: write low subslotBytes, LSB first
            val base = dstOffset + i * subslotBytes
            dst[base] = ((result ushr 0) and 0xFF).toByte()
            dst[base + 1] = ((result ushr 8) and 0xFF).toByte()
            // // ASSUMPTION: subslotBytes >= 2 is guaranteed by validation, so indices base+1 is safe
            if (subslotBytes > 2) {
                dst[base + 2] = ((result ushr 16) and 0xFF).toByte()
            }
            if (subslotBytes > 3) {
                dst[base + 3] = ((result ushr 24) and 0xFF).toByte()
            }
        }

        return sampleCount * subslotBytes
    }
}
