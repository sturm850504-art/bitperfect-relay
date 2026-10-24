package com.bitperfect.relay.output.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

// Written by Claude independently of the implementation (Phase A2 acceptance tests).
class PcmPackerTest {

    private fun pack1(x: Float, slot: Int, bits: Int): ByteArray {
        val out = ByteArray(slot)
        assertEquals(slot, PcmPacker.pack(floatArrayOf(x), 0, 1, slot, bits, out, 0))
        return out
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun briefExamples() {
        assertArrayEquals(bytes(0x00, 0x40), pack1(0.5f, 2, 16))
        assertArrayEquals(bytes(0x00, 0x00, 0x80), pack1(-1.0f, 3, 24))
        assertArrayEquals(bytes(0x00, 0xFF, 0xFF, 0x7F), pack1(1.0f, 4, 24))
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0xC0), pack1(-0.5f, 4, 32))
    }

    @Test fun clampAndNaN() {
        assertArrayEquals(bytes(0xFF, 0x7F), pack1(1.0f, 2, 16))
        assertArrayEquals(bytes(0xFF, 0x7F), pack1(3.5f, 2, 16))
        assertArrayEquals(bytes(0x00, 0x80), pack1(-7f, 2, 16))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0x7F), pack1(1.0f, 4, 32))
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x80), pack1(-1.0f, 4, 32))
        assertArrayEquals(bytes(0, 0, 0), pack1(Float.NaN, 3, 24))
        assertArrayEquals(bytes(0xFF, 0x7F), pack1(Float.POSITIVE_INFINITY, 2, 16))
    }

    @Test fun sixteenBitRoundTripIsExact() {
        // Android delivers a 16-bit source as k / 32768f; packing must restore k exactly.
        for (k in -32768..32767) {
            val out = pack1(k / 32768f, 2, 16)
            val back = (out[0].toInt() and 0xFF) or (out[1].toInt() shl 8)
            assertEquals(k, back.toShort().toInt())
        }
    }

    @Test fun twentyFourBitInto32IsLeftJustified() {
        val k = -1234567
        val out = pack1(k / 8388608f, 4, 24)
        assertEquals(0, out[0].toInt())
        val v = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8) or
            ((out[2].toInt() and 0xFF) shl 16) or (out[3].toInt() shl 24)
        assertEquals(k, v shr 8)
    }

    @Test fun oddResolution() {
        // 20 valid bits in a 4-byte slot: value << 12.
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x40), pack1(0.5f, 4, 20))
        assertArrayEquals(bytes(0x00, 0xF0, 0xFF, 0x7F), pack1(1.0f, 4, 20))
    }

    @Test fun tiesToEven() {
        assertArrayEquals(bytes(0, 0), pack1(0.5f / 32768f, 2, 16))
        assertArrayEquals(bytes(2, 0), pack1(1.5f / 32768f, 2, 16))
        assertArrayEquals(bytes(0xFE, 0xFF), pack1(-1.5f / 32768f, 2, 16))
    }

    @Test fun offsetsAndCount() {
        val src = floatArrayOf(9f, 0.5f, -0.5f, 9f)
        val dst = ByteArray(8) { 0x55 }
        assertEquals(6, PcmPacker.pack(src, 1, 2, 3, 24, dst, 1))
        assertArrayEquals(bytes(0x55, 0x00, 0x00, 0x40, 0x00, 0x00, 0xC0, 0x55), dst)
        assertEquals(0, PcmPacker.pack(src, 0, 0, 2, 16, dst, 0))
    }

    @Test(expected = IllegalArgumentException::class) fun badSubslot() { PcmPacker.pack(FloatArray(1), 0, 1, 5, 16, ByteArray(8), 0) }
    @Test(expected = IllegalArgumentException::class) fun badResolution() { PcmPacker.pack(FloatArray(1), 0, 1, 3, 25, ByteArray(8), 0) }
    @Test(expected = IllegalArgumentException::class) fun zeroResolution() { PcmPacker.pack(FloatArray(1), 0, 1, 2, 0, ByteArray(8), 0) }
    @Test(expected = IllegalArgumentException::class) fun srcOutOfRange() { PcmPacker.pack(FloatArray(2), 1, 2, 2, 16, ByteArray(8), 0) }
    @Test(expected = IllegalArgumentException::class) fun dstOutOfRange() { PcmPacker.pack(FloatArray(2), 0, 2, 4, 24, ByteArray(7), 0) }
    @Test(expected = IllegalArgumentException::class) fun negativeCount() { PcmPacker.pack(FloatArray(2), 0, -1, 2, 16, ByteArray(8), 0) }

    // Digital volume (task 2026-10-24): unity gain must leave every sample bit-exact.
    @Test fun unityGainIsBitExact() {
        val n = 4096
        val rnd = java.util.Random(7)
        val src = FloatArray(n) { (rnd.nextDouble() * 2.2 - 1.1).toFloat() }
        for ((slot, bits) in listOf(2 to 16, 3 to 24, 4 to 24, 4 to 32)) {
            val plain = ByteArray(n * slot)
            val unity = ByteArray(n * slot)
            PcmPacker.pack(src, 0, n, slot, bits, plain, 0)
            PcmPacker.pack(src, 0, n, slot, bits, unity, 0, PcmPacker.UNITY_GAIN)
            assertArrayEquals(plain, unity)
        }
    }

    @Test fun halfGainHalvesWithRounding() {
        fun packG(x: Float, g: Int): Int {
            val out = ByteArray(2)
            PcmPacker.pack(floatArrayOf(x), 0, 1, 2, 16, out, 0, g)
            return ((out[0].toInt() and 0xFF) or (out[1].toInt() shl 8)).toShort().toInt()
        }
        assertEquals(5000, packG(10000 / 32768f, 32768))
        assertEquals(2, packG(3 / 32768f, 32768))     // 1.5 rounds half up
        assertEquals(-1, packG(-3 / 32768f, 32768))   // -1.5 rounds half up
        assertEquals(-16384, packG(-1.0f, 32768))
        assertEquals(0, packG(0.9f, 0))
        // 24-bit in 4 bytes: gain is applied before the left shift, low byte stays 0.
        val out = ByteArray(4)
        PcmPacker.pack(floatArrayOf(0.5f), 0, 1, 4, 24, out, 0, 32768)
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x20), out)
    }

    @Test(expected = IllegalArgumentException::class) fun gainAboveUnity() { PcmPacker.pack(FloatArray(1), 0, 1, 2, 16, ByteArray(2), 0, 65537) }
}
