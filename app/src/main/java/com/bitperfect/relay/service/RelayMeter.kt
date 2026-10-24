package com.bitperfect.relay.service

/**
 * Read-only copy of the relayed audio for the on-screen visualizer. The capture loop pushes every
 * chunk after it went to the DAC path; nothing here touches the relayed samples.
 * Two views: the last [RAW_FRAMES] stereo frames (scope, spectrum, vectorscope) and per-block
 * summaries of the last [BLOCKS] blocks of [BLOCK_MS] ms (scrolling waveform, VU meter).
 */
object RelayMeter {
    // About 1.4 s at 96 kHz: room to delay the view by the relay's buffered audio (sync to the DAC).
    const val RAW_FRAMES = 1 shl 17
    const val BLOCK_MS = 5
    const val BLOCKS = 1400 // 7 s
    /** Floats per block: minL, maxL, minR, maxR, mean |L|, mean |R|. */
    const val BLOCK_FIELDS = 6

    private val lock = Any()
    private val raw = FloatArray(RAW_FRAMES * 2)
    private var rawPos = 0 // next frame to write
    private val blocks = FloatArray(BLOCKS * BLOCK_FIELDS)
    private var blockPos = 0 // next block to write
    private var blocksWritten = 0L

    // Block being accumulated.
    private var rate = 48000
    private var acc = 0
    private var minL = 0f; private var maxL = 0f; private var minR = 0f; private var maxR = 0f
    private var sumL = 0.0; private var sumR = 0.0

    /** Audio buffered on our side, ring plus USB transfers in flight (ms); 0 when unknown. */
    @Volatile var delayMs = 0

    /** Total blocks written so far; the UI uses it to see how many new blocks arrived. */
    val blockCount: Long get() = synchronized(lock) { blocksWritten }

    fun reset(rateHz: Int = rate) = synchronized(lock) {
        rate = rateHz
        raw.fill(0f); rawPos = 0
        blocks.fill(0f); blockPos = 0; blocksWritten = 0
        acc = 0; delayMs = 0
    }

    fun setRate(rateHz: Int) = synchronized(lock) { rate = rateHz }

    /** [n] interleaved stereo samples. */
    fun push(buf: FloatArray, n: Int) = synchronized(lock) {
        val perBlock = maxOf(1, rate * BLOCK_MS / 1000)
        var i = 0
        while (i + 1 < n) {
            val l = buf[i]; val r = buf[i + 1]
            raw[rawPos * 2] = l; raw[rawPos * 2 + 1] = r
            rawPos = (rawPos + 1) % RAW_FRAMES
            if (acc == 0) { minL = l; maxL = l; minR = r; maxR = r; sumL = 0.0; sumR = 0.0 }
            if (l < minL) minL = l; if (l > maxL) maxL = l
            if (r < minR) minR = r; if (r > maxR) maxR = r
            sumL += kotlin.math.abs(l); sumR += kotlin.math.abs(r)
            if (++acc == perBlock) {
                val o = blockPos * BLOCK_FIELDS
                blocks[o] = minL; blocks[o + 1] = maxL; blocks[o + 2] = minR; blocks[o + 3] = maxR
                blocks[o + 4] = (sumL / acc).toFloat(); blocks[o + 5] = (sumR / acc).toFloat()
                blockPos = (blockPos + 1) % BLOCKS
                blocksWritten++
                acc = 0
            }
            i += 2
        }
    }

    /**
     * Copies [frames] stereo frames, oldest first, into [dst] (size >= 2 x frames), ending
     * [skipNewest] frames before the newest one.
     */
    fun copyRaw(dst: FloatArray, frames: Int, skipNewest: Int = 0) = synchronized(lock) {
        val skip = skipNewest.coerceIn(0, RAW_FRAMES - 1)
        val f = minOf(frames, RAW_FRAMES - skip)
        var p = ((rawPos - skip - f) % RAW_FRAMES + RAW_FRAMES) % RAW_FRAMES
        for (k in 0 until f) {
            dst[k * 2] = raw[p * 2]; dst[k * 2 + 1] = raw[p * 2 + 1]
            p = (p + 1) % RAW_FRAMES
        }
    }

    /**
     * Copies [count] blocks, oldest first, into [dst] (size >= count x BLOCK_FIELDS), ending
     * [skipNewest] blocks before the newest one.
     */
    fun copyBlocks(dst: FloatArray, count: Int, skipNewest: Int = 0) = synchronized(lock) {
        val skip = skipNewest.coerceIn(0, BLOCKS - 1)
        val c = minOf(count, BLOCKS - skip)
        var p = ((blockPos - skip - c) % BLOCKS + BLOCKS) % BLOCKS
        for (k in 0 until c) {
            System.arraycopy(blocks, p * BLOCK_FIELDS, dst, k * BLOCK_FIELDS, BLOCK_FIELDS)
            p = (p + 1) % BLOCKS
        }
    }

    val sampleRate: Int get() = synchronized(lock) { rate }
}
