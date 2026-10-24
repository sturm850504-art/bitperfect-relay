package com.bitperfect.relay.output.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Feature Unit parsing (UAC1 4.3.2.5, UAC2 4.7.2.8) and volume/mute control requests
// (layout as in Linux sound/usb/mixer.c). Task 2026-10-24.
class UacVolumeTest {

    private fun b(vararg v: Int) = v.map { it.toByte() }
    private fun bytes(vararg parts: List<Byte>): ByteArray = parts.flatMap { it }.toByteArray()

    private val device = b(18, 1, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 1, 2, 3, 1)
    private val config = b(9, 2, 0, 0, 2, 1, 0, 0x80, 50)

    // UAC2: IT 2 (clock 0x29) -> FU 5 (master mute + volume, both host programmable) -> OT 6.
    private val uac2 = bytes(
        device, config,
        b(9, 4, 0, 0, 0, 1, 1, 0x20, 0),
        b(9, 0x24, 0x01, 0x00, 0x02, 0x08, 0x40, 0x00, 0x00),
        b(8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00),
        b(17, 0x24, 0x02, 0x02, 0x01, 0x01, 0x00, 0x29, 0x02, 0x03, 0, 0, 0, 0, 0x00, 0x00, 0x00),
        b(18, 0x24, 0x06, 0x05, 0x02, 0x0F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        b(12, 0x24, 0x03, 0x06, 0x02, 0x03, 0x00, 0x05, 0x29, 0, 0, 0),
        b(9, 4, 1, 0, 0, 1, 2, 0x20, 0),
        b(9, 4, 1, 1, 1, 1, 2, 0x20, 0),
        b(16, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0, 0, 0, 0x02, 0x03, 0, 0, 0, 0),
        b(6, 0x24, 0x02, 0x01, 0x04, 24),
        b(7, 5, 0x01, 0x05, 0x88, 0x01, 1),
    )

    // UAC1: IT 1 -> mixer 7 (pins 1) -> FU 3 (master mute only, volume per channel) -> OT 4.
    // A second FU 9 hangs off another input (not on the playback path) and must be ignored.
    private val uac1 = bytes(
        device, config,
        b(9, 4, 0, 0, 0, 1, 1, 0x00, 0),
        b(9, 0x24, 0x01, 0x00, 0x01, 0x30, 0x00, 0x01, 0x01),
        b(12, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0, 0),
        b(13, 0x24, 0x04, 0x07, 0x01, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00),
        b(10, 0x24, 0x06, 0x03, 0x07, 0x01, 0x01, 0x02, 0x02, 0x00),
        b(9, 0x24, 0x03, 0x04, 0x01, 0x03, 0x00, 0x03, 0x00),
        b(9, 0x24, 0x06, 0x09, 0x0B, 0x01, 0x03, 0x00, 0x00),
        b(9, 4, 1, 0, 0, 1, 2, 0x00, 0),
        b(9, 4, 1, 1, 1, 1, 2, 0x00, 0),
        b(7, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00),
        b(11, 0x24, 0x02, 0x01, 0x02, 0x02, 16, 1, 0x44, 0xAC, 0x00),
        b(9, 5, 0x01, 0x0D, 0xC4, 0x00, 1, 0, 0),
    )

    @Test fun uac2_featureUnitOnPath() {
        val d = UacDescriptors.parse(uac2)!!
        assertEquals(listOf(6), d.outputTerminals)
        val fu = d.featureUnits[5]!!
        assertEquals(2, fu.sourceId)
        assertEquals(listOf(0x0F, 0, 0), fu.controls)
        assertEquals(listOf(0), UacDescriptors.controlChannels(fu, 2, 2))
        assertEquals(listOf(0), UacDescriptors.controlChannels(fu, 2, 1))
        assertEquals(fu, UacDescriptors.volumeUnit(d, d.playback[0].terminalLink))
        assertNull(UacDescriptors.volumeUnit(d, 9)) // unknown input terminal
    }

    @Test fun uac2_readOnlyVolumeIsNotAControl() {
        val fu = UacFeatureUnit(5, 2, listOf(0x07, 0, 0)) // volume bits 0b01: readable only
        assertTrue(UacDescriptors.controlChannels(fu, 2, 2).isEmpty())
        assertEquals(listOf(0), UacDescriptors.controlChannels(fu, 2, 1))
    }

    @Test fun uac1_featureUnitThroughMixer() {
        val d = UacDescriptors.parse(uac1)!!
        val fu = d.featureUnits[3]!!
        assertEquals(listOf(0x01, 0x02, 0x02), fu.controls)
        assertEquals(listOf(1, 2), UacDescriptors.controlChannels(fu, 1, 2))
        assertEquals(listOf(0), UacDescriptors.controlChannels(fu, 1, 1))
        assertEquals(listOf(7), d.entitySources[3])
        assertEquals(listOf(1), d.entitySources[7])
        assertEquals(fu, UacDescriptors.volumeUnit(d, 1))
    }

    @Test fun noFeatureUnit_noVolume() {
        // The OT takes its signal straight from the IT.
        val raw = uac2.copyOf()
        val ot = indexOf(raw, byteArrayOf(12, 0x24, 0x03, 0x06))
        raw[ot + 7] = 0x02
        val d = UacDescriptors.parse(raw)!!
        assertNull(UacDescriptors.volumeUnit(d, 2))
    }

    private class Call(val type: Int, val req: Int, val value: Int, val index: Int, val data: ByteArray)

    private class FakeIo(val reply: (type: Int, req: Int, value: Int) -> ByteArray?) : ControlIo {
        val calls = mutableListOf<Call>()
        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int {
            calls += Call(requestType, request, value, index, buffer.copyOf(length))
            if (requestType == 0x21) return length
            val r = reply(requestType, request, value) ?: return -1
            r.copyInto(buffer, 0, 0, minOf(length, r.size))
            return minOf(length, r.size)
        }
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    @Test fun uac2_requests() {
        // RANGE: 1 sub-range, -100 dB .. 0 dB, 0.5 dB steps. CUR: -20 dB.
        val io = FakeIo { _, req, value ->
            when {
                req == 0x02 && value == 0x0200 -> le16(1) + le16(-100 * 256) + le16(0) + le16(128)
                req == 0x01 && value == 0x0200 -> le16(-20 * 256)
                req == 0x01 && value == 0x0100 -> byteArrayOf(1)
                else -> null
            }
        }
        val v = UacVolume(2, 0, UacFeatureUnit(5, 2, listOf(0x0F, 0, 0)), io)
        assertEquals(VolumeRange(-25600, 0, 128), v.readRange())
        assertEquals(0xA1, io.calls[0].type); assertEquals(0x0200, io.calls[0].value)
        assertEquals((5 shl 8) or 0, io.calls[0].index); assertEquals(8, io.calls[0].data.size)
        assertEquals(-5120, v.readVolume())
        assertTrue(v.setVolume(-30 * 256))
        val set = io.calls.last()
        assertEquals(0x21, set.type); assertEquals(0x01, set.req); assertEquals(0x0200, set.value)
        assertArrayEquals(le16(-7680), set.data)
        assertEquals(true, v.readMute())
        assertTrue(v.setMute(false))
        assertEquals(0x0100, io.calls.last().value); assertArrayEquals(byteArrayOf(0), io.calls.last().data)
    }

    @Test fun uac1_requestsPerChannel() {
        val io = FakeIo { _, req, value ->
            if (value and 0xFF00 != 0x0200) null else when (req) {
                0x82 -> le16(-0x7F00) // GET_MIN -127 dB
                0x83 -> le16(0)       // GET_MAX
                0x84 -> le16(0x0100)  // GET_RES 1 dB
                0x81 -> le16(-0x0A00) // GET_CUR -10 dB
                else -> null
            }
        }
        val v = UacVolume(1, 0, UacFeatureUnit(3, 7, listOf(0x01, 0x02, 0x02)), io)
        assertEquals(listOf(1, 2), v.volumeChannels)
        assertEquals(VolumeRange(-0x7F00, 0, 0x100), v.readRange())
        assertEquals(listOf(0x82, 0x83, 0x84), io.calls.map { it.req })
        assertTrue(io.calls.all { it.value == 0x0201 && it.index == (3 shl 8) })
        assertEquals(-0x0A00, v.readVolume())
        io.calls.clear()
        assertTrue(v.setVolume(-0x1400))
        assertEquals(listOf(0x0201, 0x0202), io.calls.map { it.value })
        assertTrue(io.calls.all { it.type == 0x21 && it.req == 0x01 })
    }

    @Test fun failedReadsGiveNull() {
        val io = FakeIo { _, _, _ -> null }
        val v = UacVolume(2, 0, UacFeatureUnit(5, 2, listOf(0x0F)), io)
        assertNull(v.readRange()); assertNull(v.readVolume()); assertNull(v.readMute())
        val none = UacVolume(2, 0, UacFeatureUnit(5, 2, listOf(0)), io)
        assertFalse(none.setVolume(0)); assertFalse(none.setMute(true))
    }

    @Test fun rangeSnap() {
        val r = VolumeRange(-25600, 0, 128)
        assertEquals(0, r.snap(500))
        assertEquals(-25600, r.snap(-99999))
        assertEquals(-128, r.snap(-100))
        assertEquals(-256, r.snap(-200))
        // Zero or negative resolution is treated as 1 by readRange; an odd range keeps max reachable.
        assertEquals(10, VolumeRange(-5, 10, 4).snap(10))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
