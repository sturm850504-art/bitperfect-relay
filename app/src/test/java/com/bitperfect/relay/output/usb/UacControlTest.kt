package com.bitperfect.relay.output.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UacControlTest {

    /** Scripted DAC: answers class requests like a device would and records every call. */
    private class FakeDac(
        var rate: Int = 44100,
        val ranges: List<Triple<Long, Long, Long>> = listOf(Triple(44100, 48000, 3900), Triple(88200, 192000, 0)),
        var valid: Int = 1,
        var selectorPin: Int = 1,
        var failSet: Boolean = false,
        var reportRate: Int? = null, // what GET CUR returns after a SET, null = the set rate
        var uac1Readable: Boolean = true,
    ) : ControlIo {
        data class Call(val type: Int, val req: Int, val value: Int, val index: Int, val len: Int, val data: List<Byte>)
        val calls = mutableListOf<Call>()

        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int {
            calls += Call(requestType, request, value, index, length, buffer.take(length))
            fun put32(v: Long, off: Int) { for (i in 0 until 4) if (off + i < length) buffer[off + i] = (v ushr (8 * i)).toByte() }
            return when {
                // UAC2 GET CUR SAM_FREQ
                requestType == 0xA1 && request == 0x01 && value == 0x0100 && (index ushr 8) == 0x29 -> {
                    put32((reportRate ?: rate).toLong(), 0); minOf(length, 4)
                }
                // UAC2 GET CUR selector
                requestType == 0xA1 && request == 0x01 && value == 0x0100 && (index ushr 8) == 0x28 -> {
                    buffer[0] = selectorPin.toByte(); 1
                }
                // UAC2 GET CUR CLOCK_VALID
                requestType == 0xA1 && request == 0x01 && value == 0x0200 -> { buffer[0] = valid.toByte(); 1 }
                // UAC2 RANGE SAM_FREQ
                requestType == 0xA1 && request == 0x02 && value == 0x0100 -> {
                    val full = ByteArray(2 + 12 * ranges.size)
                    full[0] = ranges.size.toByte()
                    ranges.forEachIndexed { i, r ->
                        for ((k, v) in listOf(r.first, r.second, r.third).withIndex())
                            for (b in 0 until 4) full[2 + 12 * i + 4 * k + b] = (v ushr (8 * b)).toByte()
                    }
                    val n = minOf(length, full.size); full.copyInto(buffer, 0, 0, n); n
                }
                // UAC2 SET CUR SAM_FREQ
                requestType == 0x21 && request == 0x01 && value == 0x0100 -> {
                    if (failSet) -1 else {
                        rate = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8) or
                            ((buffer[2].toInt() and 0xFF) shl 16) or ((buffer[3].toInt() and 0xFF) shl 24)
                        length
                    }
                }
                // UAC1 SET_CUR endpoint
                requestType == 0x22 && request == 0x01 && value == 0x0100 -> {
                    if (failSet) -1 else {
                        rate = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8) or
                            ((buffer[2].toInt() and 0xFF) shl 16)
                        length
                    }
                }
                // UAC1 GET_CUR endpoint
                requestType == 0xA2 && request == 0x81 && value == 0x0100 -> {
                    if (!uac1Readable) -1 else {
                        val r = reportRate ?: rate
                        buffer[0] = r.toByte(); buffer[1] = (r shr 8).toByte(); buffer[2] = (r shr 16).toByte(); 3
                    }
                }
                else -> -1
            }
        }
    }

    private fun ep(addr: Int) = UacEndpoint(addr, 0x05, 392, 0, 1, 0)

    private fun alt(
        n: Int, ver: Int, bits: Int, ch: Int = 2, pcm: Boolean = true, subslot: Int = 4,
        rates: List<Int> = emptyList(), range: IntRange? = null, epAttr: Int = 0, link: Int = 2,
    ) = UacAltSetting(
        interfaceNumber = 1, alternateSetting = n, uacVersion = ver, terminalLink = link, formatType = 1,
        pcm = pcm, rawData = !pcm, channels = ch, subslotBytes = subslot, bitResolution = bits,
        uac1Rates = rates, uac1RateRange = range, dataEndpoint = ep(0x01), feedbackEndpoint = null,
        epAttributes = epAttr,
    )

    // AC interface 0; terminal 2 -> selector 0x28 -> source 0x29 (rate writeable+readable, valid readable).
    private fun uac2(vararg alts: UacAltSetting, controls: Int = 0x07) = UacDevice(
        controlInterface = 0, controlVersion = 2,
        clocks = mapOf(
            0x29 to UacClockEntity(0x29, UacClockKind.SOURCE, emptyList(), controls),
            0x28 to UacClockEntity(0x28, UacClockKind.SELECTOR, listOf(0x29)),
        ),
        terminalClocks = mapOf(2 to 0x28),
        playback = alts.toList(),
    )

    @Test fun clockSource_followsSelectorPin() {
        val io = FakeDac()
        val d = uac2(alt(1, 2, 24))
        assertEquals(0x29, UacControl(d, io).clockSourceFor(d.playback[0]))
        assertTrue(io.calls.any { it.type == 0xA1 && it.value == 0x0100 && it.index == (0x28 shl 8) && it.len == 1 })
        io.selectorPin = 2 // out of range -> unknown
        assertNull(UacControl(d, io).clockSourceFor(d.playback[0]))
        assertNull(UacControl(d, io).clockSourceFor(alt(1, 2, 24, link = 9)))
    }

    @Test fun readRanges_twoStep() {
        val io = FakeDac()
        val r = UacControl(uac2(), io).readRanges(0x29)!!
        assertEquals(listOf(RateSubRange(44100, 48000, 3900), RateSubRange(88200, 192000, 0)), r)
        val rangeCalls = io.calls.filter { it.req == 0x02 }
        assertEquals(listOf(2, 26), rangeCalls.map { it.len })
        assertTrue(rangeCalls.all { it.type == 0xA1 && it.value == 0x0100 && it.index == (0x29 shl 8) })
    }

    @Test fun supportedRates_uac2AndUac1() {
        val d = uac2(alt(1, 2, 24))
        assertEquals(listOf(44100, 48000, 88200, 192000), UacControl(d, FakeDac()).supportedRates(d.playback[0]))
        val a1 = alt(1, 1, 16, rates = listOf(44100, 96000), range = null)
        assertEquals(listOf(44100, 96000), UacControl(d, FakeDac()).supportedRates(a1))
        val a2 = alt(2, 1, 16, range = 8000..48000)
        assertEquals(listOf(44100, 48000), UacControl(d, FakeDac()).supportedRates(a2))
    }

    @Test fun chooseAlt_prefersHighestBitsThatSupportRateAndChannels() {
        val d = uac2(
            alt(1, 2, 16, subslot = 2),
            alt(2, 2, 24),
            alt(3, 2, 32, pcm = false),       // raw data, not PCM
            alt(4, 2, 32, ch = 8),            // wrong channel count
        )
        val c = UacControl(d, FakeDac())
        assertEquals(2, c.chooseAlt(48000, 2)?.alternateSetting)
        assertNull(c.chooseAlt(96000, 2))     // not in ranges
        assertNull(c.chooseAlt(48000, 6))
    }

    @Test fun setRate_uac2_setsAndVerifies() {
        val io = FakeDac(rate = 44100)
        val d = uac2(alt(1, 2, 24))
        assertNull(UacControl(d, io).setRate(d.playback[0], 48000))
        assertEquals(48000, io.rate)
        val set = io.calls.single { it.type == 0x21 }
        assertEquals(0x01, set.req); assertEquals(0x0100, set.value); assertEquals((0x29 shl 8) or 0, set.index)
        assertEquals(listOf(0x80, 0xBB, 0x00, 0x00).map { it.toByte() }, set.data)
        assertTrue(io.calls.any { it.value == 0x0200 }) // validity checked
    }

    @Test fun setRate_uac2_skipsSetWhenAlreadyAtRate() {
        val io = FakeDac(rate = 48000)
        val d = uac2(alt(1, 2, 24))
        assertNull(UacControl(d, io).setRate(d.playback[0], 48000))
        assertTrue(io.calls.none { it.type == 0x21 })
    }

    @Test fun setRate_uac2_errors() {
        val d = uac2(alt(1, 2, 24))
        assertNotNull(UacControl(d, FakeDac(failSet = true)).setRate(d.playback[0], 48000))
        assertNotNull(UacControl(d, FakeDac(reportRate = 44100)).setRate(d.playback[0], 48000))
        assertNotNull(UacControl(d, FakeDac(valid = 0)).setRate(d.playback[0], 48000))
        // Rate control not writeable (controls 0x01: readable only) and current rate differs.
        val ro = uac2(alt(1, 2, 24), controls = 0x01)
        val io = FakeDac(rate = 44100)
        assertNotNull(UacControl(ro, io).setRate(ro.playback[0], 48000))
        assertTrue(io.calls.none { it.type == 0x21 })
        // Validity not readable (controls 0x03): no validity request, success.
        val nv = uac2(alt(1, 2, 24), controls = 0x03)
        val io2 = FakeDac(valid = 0)
        assertNull(UacControl(nv, io2).setRate(nv.playback[0], 48000))
        assertTrue(io2.calls.none { it.value == 0x0200 })
    }

    @Test fun setRate_uac1() {
        val d = uac2()
        val io = FakeDac(rate = 44100)
        val a = alt(1, 1, 24, rates = listOf(44100, 48000), epAttr = 0x01)
        assertNull(UacControl(d, io).setRate(a, 48000))
        val set = io.calls.single { it.type == 0x22 }
        assertEquals(0x01, set.req); assertEquals(0x0100, set.value); assertEquals(0x01, set.index)
        assertEquals(listOf(0x80, 0xBB, 0x00).map { it.toByte() }, set.data)
        // No sampling frequency control: nothing sent.
        val io2 = FakeDac()
        assertNull(UacControl(d, io2).setRate(alt(1, 1, 24, rates = listOf(48000), epAttr = 0), 48000))
        assertTrue(io2.calls.isEmpty())
        // Read-back unsupported: accepted. Read-back mismatch: error.
        assertNull(UacControl(d, FakeDac(uac1Readable = false)).setRate(a, 48000))
        assertNotNull(UacControl(d, FakeDac(reportRate = 44100)).setRate(a, 48000))
        assertNotNull(UacControl(d, FakeDac(failSet = true)).setRate(a, 48000))
    }
}
