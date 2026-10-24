package com.bitperfect.relay.output.usb

data class RateSubRange(val min: Long, val max: Long, val res: Long)

object UacRates {

    /** Common rates, ascending. */
    val STANDARD_RATES: List<Int> =
        listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000)

    /**
     * Decode a UAC2 RANGE parameter block.
     * Only the first [length] bytes of [block] are considered valid.
     * Returns complete subranges that fit, in order. Empty list if effective length < 2.
     * Subranges with max < min are dropped.
     */
    fun decodeRange(block: ByteArray, length: Int = block.size): List<RateSubRange> {
        // ASSUMPTION: if length is negative, treat as 0 (effective length < 2 -> empty).
        val effLen = minOf(length, block.size)
        if (effLen < 2) return emptyList()

        // wNumSubRanges: unsigned 16-bit, little-endian at bytes [0..1]
        val numSubRanges = ((block[0].toLong() and 0xFF) or
                ((block[1].toLong() and 0xFF) shl 8)).toInt()

        val result = ArrayList<RateSubRange>()
        var offset = 2

        for (i in 0 until numSubRanges) {
            // Need 12 more bytes; stop if they don't all fit in effLen
            if (offset + 12 > effLen) break

            val dMin = u32(block, offset)
            val dMax = u32(block, offset + 4)
            val dRes = u32(block, offset + 8)
            offset += 12

            // Drop invalid subranges where max < min
            if (dMax < dMin) continue
            result.add(RateSubRange(dMin, dMax, dRes))
        }

        return result
    }

    /**
     * True if [rateHz] is inside some subrange:
     *   min <= rate <= max, and
     *   res == 0 -> rate equals min or max
     *   res > 0  -> (rate - min) % res == 0
     */
    fun supports(ranges: List<RateSubRange>, rateHz: Int): Boolean {
        val rate = rateHz.toLong()
        for (r in ranges) {
            if (rate < r.min || rate > r.max) continue
            if (r.res == 0L) {
                if (rate == r.min || rate == r.max) return true
            } else {
                if ((rate - r.min) % r.res == 0L) return true
            }
        }
        return false
    }

    /** The [candidates] that [supports] accepts, in candidate order. */
    fun supportedOf(
        ranges: List<RateSubRange>,
        candidates: List<Int> = STANDARD_RATES
    ): List<Int> {
        return candidates.filter { supports(ranges, it) }
    }

    /** UAC1: true if [rateHz] is in [discrete], or inside [range] (inclusive) when range is not null. */
    fun supportsUac1(discrete: List<Int>, range: IntRange?, rateHz: Int): Boolean {
        if (rateHz in discrete) return true
        if (range != null && rateHz in range) return true
        return false
    }

    // -- internal helpers --

    /** Read an unsigned 32-bit little-endian value starting at [off]. Caller guarantees bounds. */
    private fun u32(b: ByteArray, off: Int): Long {
        return (b[off].toLong() and 0xFF) or
               ((b[off + 1].toLong() and 0xFF) shl 8) or
               ((b[off + 2].toLong() and 0xFF) shl 16) or
               ((b[off + 3].toLong() and 0xFF) shl 24)
    }
}
