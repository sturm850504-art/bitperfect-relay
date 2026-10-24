package com.bitperfect.relay.output.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Written by Claude independently of the implementation (Phase A2 acceptance tests).
class UacDescriptorsTest {

    private fun b(vararg v: Int) = v.map { it.toByte() }

    private fun bytes(vararg parts: List<Byte>): ByteArray = parts.flatMap { it }.toByteArray()

    private val device = b(18, 1, 0, 2, 0xEF, 2, 1, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 1, 2, 3, 1)
    private val config = b(9, 2, 0, 0, 3, 1, 0, 0x80, 50)

    // ── UAC2 device: AC + one playback AS (alts 1 PCM, 2 raw/DSD) + capture AS + HID + UAC3 AS ──
    private val uac2 = bytes(
        device, config,
        b(8, 0x0B, 0, 2, 1, 0, 0x20, 0),                       // IAD (skip)
        b(9, 4, 0, 0, 1, 1, 1, 0x20, 0),                       // AC interface, UAC2
        b(9, 0x24, 0x01, 0x00, 0x02, 0x08, 0x40, 0x00, 0x00),  // AC header
        b(8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00),        // clock source 0x29
        b(8, 0x24, 0x0B, 0x28, 0x01, 0x29, 0x03, 0x00),        // clock selector 0x28 <- [0x29]
        b(8, 0x24, 0x0C, 0x30, 0x29, 0x00, 0x00, 0x00),        // clock multiplier 0x30 <- 0x29 (bLength 7 + pad)
        b(17, 0x24, 0x02, 0x02, 0x01, 0x01, 0x00, 0x28, 0x02, 0x03, 0, 0, 0, 0, 0x00, 0x00, 0x00), // IT 2, clock 0x28
        b(12, 0x24, 0x03, 0x06, 0x01, 0x03, 0x00, 0x02, 0x28, 0, 0, 0), // OT (ignored)
        b(7, 5, 0x83, 0x03, 6, 0, 8),                          // AC interrupt EP (ignored)
        b(9, 4, 1, 0, 0, 1, 2, 0x20, 0),                       // AS if 1 alt 0 (zero-bandwidth)
        b(9, 4, 1, 1, 2, 1, 2, 0x20, 0),                       // AS if 1 alt 1
        b(16, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0, 0, 0, 0x02, 0x03, 0, 0, 0, 0), // AS_GENERAL PCM, 2ch
        b(6, 0x24, 0x02, 0x01, 0x04, 24),                      // FORMAT_TYPE I: subslot 4, 24 bit
        b(7, 5, 0x01, 0x05, 0x88, 0x01, 1),                    // OUT iso async data, 392 B
        b(8, 0x25, 0x01, 0x81, 0, 0, 0, 0),                    // CS EP: UAC2 keeps only 0x80
        b(7, 5, 0x81, 0x11, 4, 0, 4),                          // IN iso feedback
        b(9, 4, 1, 2, 2, 1, 2, 0x20, 0),                       // AS if 1 alt 2
        b(16, 0x24, 0x01, 0x02, 0x00, 0x01, 0, 0, 0, 0x80, 0x02, 0x03, 0, 0, 0, 0), // AS_GENERAL raw data
        b(6, 0x24, 0x02, 0x01, 0x04, 32),
        b(7, 5, 0x01, 0x05, 0x00, 0x0A, 1),                    // wMaxPacketSize 0x0A00: 512 B, +1 transaction
        b(7, 5, 0x81, 0x11, 4, 0, 4),
        b(9, 4, 2, 0, 0, 1, 2, 0x20, 0),                       // capture AS if 2
        b(9, 4, 2, 1, 1, 1, 2, 0x20, 0),
        b(16, 0x24, 0x01, 0x07, 0x00, 0x01, 0x01, 0, 0, 0, 0x02, 0x03, 0, 0, 0, 0),
        b(6, 0x24, 0x02, 0x01, 0x04, 24),
        b(7, 5, 0x82, 0x05, 0x88, 0x01, 1),                    // IN data only -> not playback
        b(9, 4, 3, 0, 1, 3, 0, 0, 0),                          // HID (ignored)
        b(7, 5, 0x84, 0x03, 8, 0, 10),
        b(9, 4, 4, 1, 1, 1, 2, 0x30, 0),                       // UAC3 AS (unsupported protocol)
        b(16, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0, 0, 0, 0x02, 0x03, 0, 0, 0, 0),
        b(6, 0x24, 0x02, 0x01, 0x04, 24),
        b(7, 5, 0x05, 0x05, 0x88, 0x01, 1),
        b(9, 2, 0, 0, 1, 2, 0, 0x80, 50),                      // second configuration: ignored
        b(9, 4, 5, 1, 1, 1, 2, 0x20, 0),
        b(16, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0, 0, 0, 0x02, 0x03, 0, 0, 0, 0),
        b(6, 0x24, 0x02, 0x01, 0x02, 16),
        b(7, 5, 0x06, 0x05, 0x88, 0x01, 1),
    )

    @Test fun uac2_controlAndClocks() {
        val d = UacDescriptors.parse(uac2)!!
        assertEquals(0, d.controlInterface)
        assertEquals(2, d.controlVersion)
        assertEquals(UacClockEntity(0x29, UacClockKind.SOURCE, emptyList(), 0x07), d.clocks[0x29])
        assertEquals(UacClockEntity(0x28, UacClockKind.SELECTOR, listOf(0x29)), d.clocks[0x28])
        assertEquals(UacClockEntity(0x30, UacClockKind.MULTIPLIER, listOf(0x29)), d.clocks[0x30])
        assertEquals(3, d.clocks.size)
        assertEquals(mapOf(2 to 0x28), d.terminalClocks)
    }

    @Test fun uac2_playbackAlts() {
        val d = UacDescriptors.parse(uac2)!!
        assertEquals(2, d.playback.size)
        val a1 = d.playback[0]
        assertEquals(1, a1.interfaceNumber); assertEquals(1, a1.alternateSetting)
        assertEquals(2, a1.uacVersion); assertEquals(2, a1.terminalLink); assertEquals(1, a1.formatType)
        assertTrue(a1.pcm); assertFalse(a1.rawData)
        assertEquals(2, a1.channels); assertEquals(4, a1.subslotBytes); assertEquals(24, a1.bitResolution)
        assertTrue(a1.uac1Rates.isEmpty()); assertNull(a1.uac1RateRange)
        assertEquals(UacEndpoint(0x01, 0x05, 392, 0, 1, 0), a1.dataEndpoint)
        assertEquals(UacEndpoint(0x81, 0x11, 4, 0, 4, 0), a1.feedbackEndpoint)
        assertEquals(0x80, a1.epAttributes)
        val a2 = d.playback[1]
        assertEquals(2, a2.alternateSetting)
        assertFalse(a2.pcm); assertTrue(a2.rawData); assertEquals(32, a2.bitResolution)
        assertEquals(512, a2.dataEndpoint.maxPacketBytes); assertEquals(1, a2.dataEndpoint.extraTransactions)
        assertNotNull(a2.feedbackEndpoint)
        assertEquals(0, a2.epAttributes)
    }

    @Test fun uac2_resolveClock() {
        val d = UacDescriptors.parse(uac2)!!
        assertEquals(0x29, UacDescriptors.resolveClockSource(d, 0x28))
        assertEquals(0x29, UacDescriptors.resolveClockSource(d, 0x30))
        assertEquals(0x29, UacDescriptors.resolveClockSource(d, 0x29))
        assertNull(UacDescriptors.resolveClockSource(d, 0x28) { 2 })   // pin out of range
        assertNull(UacDescriptors.resolveClockSource(d, 0x55))         // unknown id
    }

    @Test fun resolveClock_loopGuard() {
        val d = UacDevice(
            0, 2,
            mapOf(
                1 to UacClockEntity(1, UacClockKind.SELECTOR, listOf(2)),
                2 to UacClockEntity(2, UacClockKind.MULTIPLIER, listOf(1)),
            ),
            emptyMap(), emptyList(),
        )
        assertNull(UacDescriptors.resolveClockSource(d, 1))
    }

    // ── UAC1 device ──
    private val uac1 = bytes(
        device, config,
        b(9, 4, 0, 0, 0, 1, 1, 0x00, 0),                       // AC, UAC1
        b(9, 0x24, 0x01, 0x00, 0x01, 0x1E, 0x00, 0x01, 0x01),
        b(12, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0, 0), // UAC1 IT: must be ignored
        b(9, 4, 1, 0, 0, 1, 2, 0x00, 0),
        b(9, 4, 1, 1, 1, 1, 2, 0x00, 0),
        b(7, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00),              // AS_GENERAL, PCM
        b(17, 0x24, 0x02, 0x01, 0x02, 0x03, 24, 3, 0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00, 0x00, 0x77, 0x01),
        b(9, 5, 0x01, 0x0D, 0x20, 0x02, 1, 0, 0),              // OUT iso sync, no feedback
        b(7, 0x25, 0x01, 0x01, 0x00, 0x00, 0x00),
        b(9, 4, 1, 2, 2, 1, 2, 0x00, 0),
        b(7, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00),
        b(14, 0x24, 0x02, 0x01, 0x02, 0x02, 16, 0, 0x40, 0x1F, 0x00, 0x00, 0x77, 0x01), // continuous 8000..96000
        b(9, 5, 0x02, 0x05, 0xC4, 0x00, 1, 0, 0x82),           // OUT iso async, synch 0x82
        b(9, 5, 0x82, 0x01, 3, 0, 1, 2, 0),                    // IN iso, usage 0, matched by address
        b(9, 4, 1, 3, 1, 1, 2, 0x00, 0),
        b(7, 0x24, 0x01, 0x01, 0x01, 0x01, 0x10),              // wFormatTag 0x1001: not PCM
        b(11, 0x24, 0x02, 0x01, 0x02, 0x02, 16, 1, 0x80, 0xBB, 0x00),
        b(9, 5, 0x01, 0x09, 0xC0, 0x00, 1, 0, 0),
    )

    @Test fun uac1_parse() {
        val d = UacDescriptors.parse(uac1)!!
        assertEquals(1, d.controlVersion)
        assertTrue(d.clocks.isEmpty()); assertTrue(d.terminalClocks.isEmpty())
        assertEquals(3, d.playback.size)
        val a1 = d.playback[0]
        assertEquals(1, a1.uacVersion); assertEquals(1, a1.terminalLink)
        assertTrue(a1.pcm); assertFalse(a1.rawData)
        assertEquals(2, a1.channels); assertEquals(3, a1.subslotBytes); assertEquals(24, a1.bitResolution)
        assertEquals(listOf(44100, 48000, 96000), a1.uac1Rates); assertNull(a1.uac1RateRange)
        assertEquals(UacEndpoint(0x01, 0x0D, 544, 0, 1, 0), a1.dataEndpoint)
        assertNull(a1.feedbackEndpoint)
        assertEquals(0x01, a1.epAttributes)
        val a2 = d.playback[1]
        assertTrue(a2.uac1Rates.isEmpty()); assertEquals(8000..96000, a2.uac1RateRange)
        assertEquals(0x82, a2.dataEndpoint.synchAddress)
        assertEquals(0x82, a2.feedbackEndpoint?.address)
        assertEquals(0, a2.epAttributes)
        assertFalse(d.playback[2].pcm)
    }

    // ── malformed input ──
    @Test fun empty_isNull() = assertNull(UacDescriptors.parse(ByteArray(0)))

    @Test fun noControlInterface_isNull() {
        val raw = bytes(device, config,
            b(9, 4, 1, 1, 1, 1, 2, 0x20, 0),
            b(16, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0, 0, 0, 0x02, 0x03, 0, 0, 0, 0),
            b(6, 0x24, 0x02, 0x01, 0x04, 24),
            b(7, 5, 0x01, 0x05, 0x88, 0x01, 1))
        assertNull(UacDescriptors.parse(raw))
    }

    @Test fun noPlayback_isNull() {
        val raw = bytes(device, config, b(9, 4, 0, 0, 0, 1, 1, 0x20, 0), b(8, 0x24, 0x0A, 0x29, 3, 7, 0, 0))
        assertNull(UacDescriptors.parse(raw))
    }

    @Test fun truncatedBeforeEndpoint_dropsIncompleteAlt() {
        // Cut right after alt 2's FORMAT_TYPE: alt 2 has no endpoint, alt 1 survives.
        val marker = byteArrayOf(6, 0x24, 0x02, 0x01, 0x04, 32)
        val cut = indexOf(uac2, marker) + marker.size
        val d = UacDescriptors.parse(uac2.copyOf(cut))!!
        assertEquals(listOf(1), d.playback.map { it.alternateSetting })
    }

    @Test fun overrunningDescriptor_stopsWalk() {
        val d = UacDescriptors.parse(uac2.copyOf(indexOf(uac2, byteArrayOf(7, 5, 0x81.toByte(), 0x11)) + 3))
        // alt 1's data endpoint is complete, its feedback descriptor is cut: alt 1 kept, feedback null.
        assertEquals(1, d!!.playback.size)
        assertNull(d.playback[0].feedbackEndpoint)
    }

    @Test fun zeroLength_doesNotHang() {
        val raw = bytes(device, config, b(9, 4, 0, 0, 0, 1, 1, 0x20, 0), b(0, 0x24, 0, 0), b(9, 4, 1, 1, 1, 1, 2, 0x20, 0))
        assertNull(UacDescriptors.parse(raw))
    }

    @Test fun selectorPinsBoundedByLength() {
        // bNrInPins says 3 but bLength holds one pin: only bytes inside the descriptor count.
        val raw = bytes(device, config,
            b(9, 4, 0, 0, 0, 1, 1, 0x20, 0),
            b(6, 0x24, 0x0B, 0x28, 3, 0x29),
            b(8, 0x24, 0x0A, 0x29, 3, 7, 0, 0),
            b(9, 4, 1, 1, 1, 1, 2, 0x20, 0),
            b(16, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0, 0, 0, 0x02, 0x03, 0, 0, 0, 0),
            b(6, 0x24, 0x02, 0x01, 0x04, 24),
            b(7, 5, 0x01, 0x05, 0x88, 0x01, 1))
        assertEquals(listOf(0x29), UacDescriptors.parse(raw)!!.clocks[0x28]!!.inputs)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        error("needle not found")
    }
}
