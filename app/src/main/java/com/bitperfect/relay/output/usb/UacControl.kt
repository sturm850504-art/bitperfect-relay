package com.bitperfect.relay.output.usb

/** Control transfer on endpoint 0, same contract as Android UsbDeviceConnection.controlTransfer
 *  without the timeout: returns bytes transferred, negative on failure. */
fun interface ControlIo {
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int
}

class UacControl(private val dev: UacDevice, private val io: ControlIo) {

    private companion object {
        const val CLASS_IFACE_OUT = 0x21
        const val CLASS_IFACE_IN = 0xA1
        const val CLASS_EP_OUT = 0x22
        const val CLASS_EP_IN = 0xA2
        const val REQ_CUR = 0x01
        const val REQ_RANGE = 0x02
        const val REQ_UAC1_GET_CUR = 0x81
        const val CTRL_SAM_FREQ = 1
        const val CTRL_CLOCK_VALID = 2
        const val CLOCK_VALID_TRIES = 10
    }

    fun clockSourceFor(alt: UacAltSetting): Int? {
        if (alt.uacVersion != 2) return null
        val startEntity = dev.terminalClocks[alt.terminalLink] ?: return null
        return UacDescriptors.resolveClockSource(dev, startEntity) { selectorId ->
            readSelectorPin(selectorId)
        }
    }

    fun readRanges(clockId: Int): List<RateSubRange>? {
        val numBuf = ByteArray(2)
        val n = io.transfer(
            CLASS_IFACE_IN, REQ_RANGE, CTRL_SAM_FREQ shl 8,
            clockId shl 8 or dev.controlInterface, numBuf, 2
        )
        if (n < 2) return null

        val subRanges = (numBuf[0].toInt() and 0xFF) or ((numBuf[1].toInt() and 0xFF) shl 8)
        if (subRanges == 0) return emptyList()

        val totalLen = 2 + 12 * subRanges
        val buf = ByteArray(totalLen)
        val r = io.transfer(
            CLASS_IFACE_IN, REQ_RANGE, CTRL_SAM_FREQ shl 8,
            clockId shl 8 or dev.controlInterface, buf, totalLen
        )
        if (r < totalLen) return null
        return UacRates.decodeRange(buf, r)
    }

    fun readRate(clockId: Int): Int? {
        val buf = ByteArray(4)
        val r = io.transfer(
            CLASS_IFACE_IN, REQ_CUR, CTRL_SAM_FREQ shl 8,
            clockId shl 8 or dev.controlInterface, buf, 4
        )
        if (r < 4) return null
        return (buf[0].toInt() and 0xFF) or
               ((buf[1].toInt() and 0xFF) shl 8) or
               ((buf[2].toInt() and 0xFF) shl 16) or
               ((buf[3].toInt() and 0xFF) shl 24)
    }

    fun supportedRates(alt: UacAltSetting, candidates: List<Int> = UacRates.STANDARD_RATES): List<Int> {
        return when (alt.uacVersion) {
            1 -> candidates.filter { UacRates.supportsUac1(alt.uac1Rates, alt.uac1RateRange, it) }
            2 -> {
                val clock = clockSourceFor(alt) ?: return emptyList()
                val ranges = readRanges(clock) ?: return emptyList()
                UacRates.supportedOf(ranges, candidates)
            }
            else -> emptyList()
        }
    }

    fun chooseAlt(rateHz: Int, channels: Int): UacAltSetting? {
        // Cache UAC2 range lookups so each clock is queried at most once per call.
        val rangesCache = mutableMapOf<Int, List<RateSubRange>?>()

        var best: UacAltSetting? = null
        var bestBitRes = -1

        for (alt in dev.playback) {
            if (!alt.pcm) continue
            if (alt.formatType != 1) continue
            if (alt.channels != channels) continue
            if (alt.subslotBytes !in 2..4) continue

            val supported = when (alt.uacVersion) {
                1 -> UacRates.supportsUac1(alt.uac1Rates, alt.uac1RateRange, rateHz)
                2 -> {
                    val clock = clockSourceFor(alt) ?: continue
                    val ranges = rangesCache.getOrPut(clock) { readRanges(clock) } ?: continue
                    UacRates.supports(ranges, rateHz)
                }
                else -> false
            }
            if (!supported) continue

            if (alt.bitResolution > bestBitRes) {
                bestBitRes = alt.bitResolution
                best = alt
            }
        }
        return best
    }

    fun setRate(alt: UacAltSetting, rateHz: Int): String? {
        return when (alt.uacVersion) {
            1 -> setRateUac1(alt, rateHz)
            2 -> setRateUac2(alt, rateHz)
            else -> "unsupported UAC version"
        }
    }

    // --- private helpers ---

    private fun readSelectorPin(selectorId: Int): Int {
        val buf = ByteArray(1)
        val r = io.transfer(
            CLASS_IFACE_IN, REQ_CUR, CTRL_SAM_FREQ shl 8,
            selectorId shl 8 or dev.controlInterface, buf, 1
        )
        if (r < 1) return 0
        return buf[0].toInt() and 0xFF
    }

    private fun setRateUac2(alt: UacAltSetting, rateHz: Int): String? {
        val clockId = clockSourceFor(alt) ?: return "cannot resolve clock source"
        val entity = dev.clocks[clockId]
        val controls = entity?.controls ?: 0

        // Step 2: read current rate; if already correct, jump to step 5.
        val cur = readRate(clockId)
        if (cur != rateHz) {
            // Step 3: SAM_FREQ must be writeable (bit 1 of control N=1).
            if (controls and 0x02 == 0) {
                return "sample rate is fixed at ${cur ?: "unknown"} Hz"
            }

            // Step 4: SET CUR, then verify.
            val rateBuf = ByteArray(4)
            rateBuf[0] = (rateHz and 0xFF).toByte()
            rateBuf[1] = ((rateHz ushr 8) and 0xFF).toByte()
            rateBuf[2] = ((rateHz ushr 16) and 0xFF).toByte()
            rateBuf[3] = ((rateHz ushr 24) and 0xFF).toByte()

            val sr = io.transfer(
                CLASS_IFACE_OUT, REQ_CUR, CTRL_SAM_FREQ shl 8,
                clockId shl 8 or dev.controlInterface, rateBuf, 4
            )
            if (sr < 0) return "failed to set sample rate"

            val after = readRate(clockId)
            if (after != null && after != rateHz) {
                return "requested ${rateHz} Hz but device reports $after Hz"
            }
        }

        // Step 5: CLOCK_VALID readable (bit 2 of control N=2) → must be nonzero.
        // Read while the streaming interface is at alt 0 (UAPP order); [AI judgment] give the DAC
        // up to about 200 ms to lock after a rate change before calling it invalid.
        if (controls and 0x04 != 0) {
            val buf = ByteArray(1)
            var valid = false
            for (attempt in 0 until CLOCK_VALID_TRIES) {
                val vr = io.transfer(
                    CLASS_IFACE_IN, REQ_CUR, CTRL_CLOCK_VALID shl 8,
                    clockId shl 8 or dev.controlInterface, buf, 1
                )
                if (vr >= 1 && buf[0].toInt() != 0) { valid = true; break }
                if (attempt < CLOCK_VALID_TRIES - 1) Thread.sleep(20)
            }
            if (!valid) return "clock not valid"
        }

        return null
    }

    private fun setRateUac1(alt: UacAltSetting, rateHz: Int): String? {
        // No endpoint rate control → nothing to do, succeed.
        if (alt.epAttributes and 0x01 == 0) return null

        val epAddr = alt.dataEndpoint.address
        val rateBuf = ByteArray(3)
        rateBuf[0] = (rateHz and 0xFF).toByte()
        rateBuf[1] = ((rateHz ushr 8) and 0xFF).toByte()
        rateBuf[2] = ((rateHz ushr 16) and 0xFF).toByte()

        val sr = io.transfer(CLASS_EP_OUT, REQ_CUR, CTRL_SAM_FREQ shl 8, epAddr, rateBuf, 3)
        if (sr < 0) return "failed to set sample rate"

        // Read back; many devices fail or return 0 — treat both as success.
        val readBuf = ByteArray(3)
        val gr = io.transfer(CLASS_EP_IN, REQ_UAC1_GET_CUR, CTRL_SAM_FREQ shl 8, epAddr, readBuf, 3)
        if (gr < 3) return null
        val reported = (readBuf[0].toInt() and 0xFF) or
                       ((readBuf[1].toInt() and 0xFF) shl 8) or
                       ((readBuf[2].toInt() and 0xFF) shl 16)
        if (reported == 0) return null
        if (reported != rateHz) {
            return "requested ${rateHz} Hz but device reports $reported Hz"
        }
        return null
    }
}
