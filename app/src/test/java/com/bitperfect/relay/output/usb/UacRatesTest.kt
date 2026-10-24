package com.bitperfect.relay.output.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Written by Claude independently of the implementation (Phase A2 acceptance tests).
class UacRatesTest {

    private fun block(n: Int, vararg triplets: Long): ByteArray {
        val out = ArrayList<Byte>()
        out += (n and 0xFF).toByte(); out += ((n shr 8) and 0xFF).toByte()
        for (v in triplets) for (i in 0 until 4) out += ((v shr (8 * i)) and 0xFF).toByte()
        return out.toByteArray()
    }

    @Test fun decodeTwoSubranges() {
        val b = block(2, 44100, 44100, 0, 48000, 192000, 48000)
        assertEquals(
            listOf(RateSubRange(44100, 44100, 0), RateSubRange(48000, 192000, 48000)),
            UacRates.decodeRange(b),
        )
    }

    @Test fun shortReplyKeepsCompleteOnly() {
        val b = block(2, 44100, 44100, 0, 48000, 192000, 48000)
        assertEquals(1, UacRates.decodeRange(b, 2 + 12 + 11).size)
        assertEquals(1, UacRates.decodeRange(b.copyOf(2 + 12 + 5)).size)
    }

    @Test fun announcedCountLimits() {
        val b = block(1, 44100, 44100, 0, 48000, 48000, 0)
        assertEquals(1, UacRates.decodeRange(b).size)
    }

    @Test fun lengthBeyondArrayIsSafe() {
        val b = block(3, 44100, 44100, 0)
        assertEquals(1, UacRates.decodeRange(b, 1000).size)
    }

    @Test fun tinyOrEmpty() {
        assertTrue(UacRates.decodeRange(ByteArray(0)).isEmpty())
        assertTrue(UacRates.decodeRange(byteArrayOf(1), 1).isEmpty())
        assertTrue(UacRates.decodeRange(block(2, 44100, 44100, 0), 1).isEmpty())
    }

    @Test fun unsignedAndInvalid() {
        val b = block(2, 0xFFFFFFF0L, 0xFFFFFFFFL, 1, 96000, 48000, 0)
        val r = UacRates.decodeRange(b)
        assertEquals(listOf(RateSubRange(0xFFFFFFF0L, 0xFFFFFFFFL, 1)), r)  // max<min dropped
    }

    @Test fun supportsRules() {
        val r = listOf(RateSubRange(44100, 88200, 0), RateSubRange(48000, 192000, 48000))
        assertTrue(UacRates.supports(r, 44100)); assertTrue(UacRates.supports(r, 88200))
        assertFalse(UacRates.supports(r, 66150))
        assertTrue(UacRates.supports(r, 96000)); assertTrue(UacRates.supports(r, 192000))
        assertFalse(UacRates.supports(r, 176400)); assertFalse(UacRates.supports(r, 384000))
        assertEquals(listOf(44100, 48000, 88200, 96000, 192000), UacRates.supportedOf(r))
        assertEquals(listOf(96000, 44100), UacRates.supportedOf(r, listOf(96000, 22050, 44100)))
        assertTrue(UacRates.supportedOf(emptyList()).isEmpty())
    }

    @Test fun continuousRes1() {
        val r = listOf(RateSubRange(8000, 384000, 1))
        assertEquals(UacRates.STANDARD_RATES.filter { it <= 384000 }, UacRates.supportedOf(r))
    }

    @Test fun uac1() {
        assertTrue(UacRates.supportsUac1(listOf(44100, 48000), null, 48000))
        assertFalse(UacRates.supportsUac1(listOf(44100, 48000), null, 96000))
        assertTrue(UacRates.supportsUac1(emptyList(), 8000..96000, 96000))
        assertFalse(UacRates.supportsUac1(emptyList(), 8000..96000, 96001))
        assertFalse(UacRates.supportsUac1(emptyList(), null, 44100))
    }
}
