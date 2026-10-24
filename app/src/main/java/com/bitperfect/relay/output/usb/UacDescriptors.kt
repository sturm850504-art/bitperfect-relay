package com.bitperfect.relay.output.usb

data class UacEndpoint(
    val address: Int,
    val attributes: Int,
    val maxPacketBytes: Int,
    val extraTransactions: Int,
    val interval: Int,
    val synchAddress: Int,
)

data class UacAltSetting(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val uacVersion: Int,
    val terminalLink: Int,
    val formatType: Int,
    val pcm: Boolean,
    val rawData: Boolean,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val uac1Rates: List<Int>,
    val uac1RateRange: IntRange?,
    val dataEndpoint: UacEndpoint,
    val feedbackEndpoint: UacEndpoint?,
    // CS_ENDPOINT EP_GENERAL bmAttributes in UAC1 terms (0x01 rate control, 0x80 MaxPacketsOnly).
    // UAC2 keeps only 0x80, as Linux parse_uac_endpoint_attributes does. 0 if absent.
    val epAttributes: Int = 0,
)

enum class UacClockKind { SOURCE, SELECTOR, MULTIPLIER }

data class UacClockEntity(
    val id: Int,
    val kind: UacClockKind,
    val inputs: List<Int>,
    val controls: Int = 0, // SOURCE: bmControls (2 bits per control: readable, writeable)
)

/**
 * Feature Unit (UAC1 spec 4.3.2.5, UAC2 spec 4.7.2.8; parsed like Linux sound/usb/mixer.c
 * parse_audio_feature_unit). controls[0] is the master channel, controls[n] logical channel n.
 * UAC1: bitmap, bit 0 mute, bit 1 volume. UAC2: 2 bits per control (bit 0 readable, bit 1
 * writable), control selector c at bits 2(c-1)..2(c-1)+1: mute = c 1, volume = c 2.
 */
data class UacFeatureUnit(val id: Int, val sourceId: Int, val controls: List<Int>)

data class UacDevice(
    val controlInterface: Int,
    val controlVersion: Int,
    val clocks: Map<Int, UacClockEntity>,
    val terminalClocks: Map<Int, Int>,
    val playback: List<UacAltSetting>,
    // AC topology: unit or output terminal id -> its source ids (terminals and units share one id space).
    val entitySources: Map<Int, List<Int>> = emptyMap(),
    val outputTerminals: List<Int> = emptyList(),
    val featureUnits: Map<Int, UacFeatureUnit> = emptyMap(),
)

object UacDescriptors {

    private data class AltAccumulator(
        var terminalLink: Int = 0,
        var formatType: Int = 0,
        var pcm: Boolean = false,
        var rawData: Boolean = false,
        var channels: Int = 0,
        var subslotBytes: Int = 0,
        var bitResolution: Int = 0,
        var uac1Rates: List<Int> = emptyList(),
        var uac1RateRange: IntRange? = null,
        var hasAsGeneral: Boolean = false,
        var hasFormatType: Boolean = false,
        var endpoints: MutableList<UacEndpoint> = mutableListOf(),
        var uacVersion: Int = 0,
        var epAttributes: Int? = null,
    )

    fun parse(raw: ByteArray): UacDevice? {
        if (raw.size < 2) return null

        // Skip the device descriptor (first descriptor in the array)
        var offset = 0
        val deviceLen = raw[offset].toInt() and 0xFF
        if (deviceLen < 2 || deviceLen > raw.size) return null
        offset += deviceLen

        // Find the first configuration descriptor
        var configStart = -1
        while (offset < raw.size) {
            val len = raw[offset].toInt() and 0xFF
            if (len < 2 || offset + len > raw.size) break
            if (raw[offset + 1].toInt() and 0xFF == 0x02) { configStart = offset; break }
            offset += len
        }
        if (configStart < 0) return null

        // Helpers
        fun u8(arr: ByteArray, off: Int): Int = arr[off].toInt() and 0xFF
        fun le16(arr: ByteArray, off: Int): Int = u8(arr, off) or (u8(arr, off + 1) shl 8)
        fun le24(arr: ByteArray, off: Int): Int = u8(arr, off) or (u8(arr, off + 1) shl 8) or (u8(arr, off + 2) shl 16)
        fun le32(arr: ByteArray, off: Int): Int =
            u8(arr, off) or (u8(arr, off + 1) shl 8) or (u8(arr, off + 2) shl 16) or (u8(arr, off + 3) shl 24)

        // Track current interface context
        var curClass = 0
        var curSubclass = 0
        var curProtocol = 0
        var curIfaceNum = 0
        var curAltSetting = 0
        var curIsAudio = false
        var curAudioOk = false // protocol is 0x00 or 0x20

        // Accumulators
        var controlIface: Int? = null
        var controlVer: Int? = null
        val clocks = mutableMapOf<Int, UacClockEntity>()
        val terminalClocks = mutableMapOf<Int, Int>()
        val entitySources = mutableMapOf<Int, List<Int>>()
        val outputTerminals = mutableListOf<Int>()
        val featureUnits = mutableMapOf<Int, UacFeatureUnit>()
        val altList = mutableListOf<Pair<Pair<Int, Int>, AltAccumulator>>()

        fun getOrCreateAlt(ifNum: Int, alt: Int, ver: Int): AltAccumulator {
            val key = Pair(ifNum, alt)
            val found = altList.find { it.first == key }
            if (found != null) return found.second
            val acc = AltAccumulator().apply { uacVersion = ver }
            altList.add(Pair(key, acc))
            return acc
        }

        // Walk configuration descriptors starting at configStart
        var pos = configStart
        while (pos < raw.size) {
            val len = u8(raw, pos)
            if (len < 2 || pos + len > raw.size) break
            val dType = u8(raw, pos + 1)

            // Stop at second configuration descriptor
            if (dType == 0x02 && pos != configStart) break

            when (dType) {
                0x04 -> { // Interface
                    if (len >= 9) {
                        curIfaceNum = u8(raw, pos + 2)
                        curAltSetting = u8(raw, pos + 3)
                        // [4] bNumEndpoints
                        curClass = u8(raw, pos + 5)
                        curSubclass = u8(raw, pos + 6)
                        curProtocol = u8(raw, pos + 7)
                    }
                    curIsAudio = (curClass == 0x01)
                    curAudioOk = curIsAudio && (curProtocol == 0x00 || curProtocol == 0x20)

                    if (curIsAudio && curSubclass == 0x01 && curAudioOk && controlIface == null) {
                        controlIface = curIfaceNum
                        controlVer = if (curProtocol == 0x20) 2 else 1
                    }
                }

                0x05 -> { // Endpoint
                    if (!curIsAudio || !curAudioOk) { /* skip */ }
                    else if (len >= 7) {
                        val address = u8(raw, pos + 2)
                        val attrs = u8(raw, pos + 3)
                        val wpkt = le16(raw, pos + 4)
                        val interval = u8(raw, pos + 6)
                        val sync = if (len >= 9) u8(raw, pos + 8) else 0
                        val ep = UacEndpoint(
                            address = address,
                            attributes = attrs,
                            maxPacketBytes = wpkt and 0x07FF,
                            extraTransactions = (wpkt ushr 11) and 0x03,
                            interval = interval,
                            synchAddress = sync,
                        )
                        if (curSubclass == 0x02 && curAltSetting > 0) {
                            val acc = getOrCreateAlt(curIfaceNum, curAltSetting, if (curProtocol == 0x20) 2 else 1)
                            acc.endpoints.add(ep)
                        }
                    }
                }

                0x25 -> { // CS_ENDPOINT: keep the first EP_GENERAL of a streaming alt setting
                    if (curIsAudio && curAudioOk && curSubclass == 0x02 && curAltSetting > 0 &&
                        len >= 7 && u8(raw, pos + 2) == 0x01
                    ) {
                        val v2 = curProtocol == 0x20
                        val acc = getOrCreateAlt(curIfaceNum, curAltSetting, if (v2) 2 else 1)
                        if (acc.epAttributes == null) {
                            val attrs = u8(raw, pos + 3)
                            acc.epAttributes = if (v2) attrs and 0x80 else attrs
                        }
                    }
                }

                0x24 -> { // CS_INTERFACE
                    if (!curIsAudio || !curAudioOk) { /* skip */ }
                    else if (len >= 3) {
                        val sub = u8(raw, pos + 2)
                        if (curSubclass == 0x01 && curIfaceNum == controlIface && len >= 5) {
                            // AC topology for the volume path (UAC1 4.3.2, UAC2 4.7.2 unit layouts).
                            val v2 = curProtocol == 0x20
                            val id = u8(raw, pos + 3)
                            fun pins(countOff: Int): List<Int> {
                                if (countOff >= len) return emptyList()
                                val p = u8(raw, pos + countOff)
                                return (0 until p).filter { countOff + 1 + it < len }.map { u8(raw, pos + countOff + 1 + it) }
                            }
                            when {
                                sub == 0x03 && len >= 9 -> { // Output Terminal: bSourceID at 7 (both versions)
                                    outputTerminals += id
                                    entitySources[id] = listOf(u8(raw, pos + 7))
                                }
                                sub == 0x04 || sub == 0x05 -> entitySources[id] = pins(4) // Mixer, Selector
                                sub == 0x06 -> { // Feature Unit
                                    val src = u8(raw, pos + 4)
                                    entitySources[id] = listOf(src)
                                    val controls = mutableListOf<Int>()
                                    if (!v2 && len >= 7) {
                                        val n = u8(raw, pos + 5)
                                        if (n in 1..4) {
                                            val count = (len - 7) / n
                                            for (c in 0 until count) {
                                                var m = 0
                                                for (k in 0 until n) m = m or (u8(raw, pos + 6 + c * n + k) shl (8 * k))
                                                controls += m
                                            }
                                        }
                                    } else if (v2) {
                                        val count = (len - 6) / 4
                                        for (c in 0 until count) controls += le32(raw, pos + 5 + c * 4)
                                    }
                                    featureUnits[id] = UacFeatureUnit(id, src, controls)
                                }
                                !v2 && (sub == 0x07 || sub == 0x08) -> entitySources[id] = pins(6) // Processing, Extension
                                v2 && sub == 0x07 && len >= 7 -> entitySources[id] = listOf(u8(raw, pos + 6)) // Effect
                                v2 && (sub == 0x08 || sub == 0x09) -> entitySources[id] = pins(6) // Processing, Extension
                                v2 && sub == 0x0D -> entitySources[id] = listOf(u8(raw, pos + 4)) // Sample rate converter
                            }
                        }
                        when {
                            curSubclass == 0x01 && curProtocol == 0x20 -> {
                                // UAC2 AudioControl
                                when (sub) {
                                    0x02 -> { // Input Terminal
                                        if (len >= 8) {
                                            val tid = u8(raw, pos + 3)
                                            val csid = u8(raw, pos + 7)
                                            terminalClocks[tid] = csid
                                        }
                                    }
                                    0x0A -> { // Clock Source
                                        if (len >= 4) {
                                            val cid = u8(raw, pos + 3)
                                            val ctl = if (len >= 6) u8(raw, pos + 5) else 0
                                            clocks[cid] = UacClockEntity(cid, UacClockKind.SOURCE, emptyList(), ctl)
                                        }
                                    }
                                    0x0B -> { // Clock Selector
                                        if (len >= 5) {
                                            val cid = u8(raw, pos + 3)
                                            val pins = u8(raw, pos + 4)
                                            val inputs = (0 until minOf(pins, len - 5)).map {
                                                if (5 + it < len) u8(raw, pos + 5 + it) else 0
                                            }
                                            clocks[cid] = UacClockEntity(cid, UacClockKind.SELECTOR, inputs)
                                        }
                                    }
                                    0x0C -> { // Clock Multiplier
                                        if (len >= 5) {
                                            val cid = u8(raw, pos + 3)
                                            val src = u8(raw, pos + 4)
                                            clocks[cid] = UacClockEntity(cid, UacClockKind.MULTIPLIER, listOf(src))
                                        }
                                    }
                                }
                            }
                            curSubclass == 0x02 && curProtocol == 0x00 && curAltSetting > 0 -> {
                                // UAC1 AudioStreaming
                                val acc = getOrCreateAlt(curIfaceNum, curAltSetting, 1)
                                when (sub) {
                                    0x01 -> { // AS_GENERAL
                                        if (len >= 7) {
                                            acc.hasAsGeneral = true
                                            acc.terminalLink = u8(raw, pos + 3)
                                            val wft = le16(raw, pos + 5)
                                            acc.pcm = (wft == 0x0001)
                                            acc.rawData = false
                                        }
                                    }
                                    0x02 -> { // FORMAT_TYPE I
                                        if (len >= 8) {
                                            acc.hasFormatType = true
                                            acc.formatType = u8(raw, pos + 3)
                                            acc.channels = u8(raw, pos + 4)
                                            acc.subslotBytes = u8(raw, pos + 5)
                                            acc.bitResolution = u8(raw, pos + 6)
                                            val sfType = u8(raw, pos + 7)
                                            if (sfType == 0) {
                                                if (len >= 14) {
                                                    acc.uac1RateRange = IntRange(le24(raw, pos + 8), le24(raw, pos + 11))
                                                }
                                            } else {
                                                val rates = mutableListOf<Int>()
                                                for (i in 0 until sfType) {
                                                    val off = pos + 8 + i * 3
                                                    if (off + 3 <= pos + len) rates.add(le24(raw, off))
                                                }
                                                acc.uac1Rates = rates
                                            }
                                        }
                                    }
                                }
                            }
                            curSubclass == 0x02 && curProtocol == 0x20 && curAltSetting > 0 -> {
                                // UAC2 AudioStreaming
                                val acc = getOrCreateAlt(curIfaceNum, curAltSetting, 2)
                                when (sub) {
                                    0x01 -> { // AS_GENERAL
                                        if (len >= 16) {
                                            acc.hasAsGeneral = true
                                            acc.terminalLink = u8(raw, pos + 3)
                                            val bmFormats = le32(raw, pos + 6)
                                            acc.pcm = (bmFormats and 0x00000001) != 0
                                            acc.rawData = (bmFormats and 0x80000000.toInt()) != 0
                                            acc.channels = u8(raw, pos + 10)
                                        }
                                    }
                                    0x02 -> { // FORMAT_TYPE I (UAC2)
                                        if (len >= 6) {
                                            acc.hasFormatType = true
                                            acc.formatType = u8(raw, pos + 3)
                                            acc.subslotBytes = u8(raw, pos + 4)
                                            acc.bitResolution = u8(raw, pos + 5)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            pos += len
        }

        // Must have a control interface
        if (controlIface == null) return null

        // Resolve playback alt settings
        val playback = mutableListOf<UacAltSetting>()
        for ((key, acc) in altList) {
            if (!acc.hasAsGeneral || !acc.hasFormatType) continue

            // Find data endpoint: isochronous OUT, usage type 0
            val dataEp = acc.endpoints.find { ep ->
                (ep.attributes and 0x03) == 0x01 && // isochronous
                (ep.address and 0x80) == 0 &&       // OUT
                ((ep.attributes ushr 4) and 0x03) == 0 // usage type data
            } ?: continue

            // Find feedback endpoint
            var fbEp: UacEndpoint? = acc.endpoints.find { ep ->
                (ep.attributes and 0x03) == 0x01 && // isochronous
                (ep.address and 0x80) != 0 &&       // IN
                ((ep.attributes ushr 4) and 0x03) == 1 // usage type feedback
            }
            // UAC1: also accept isoch IN with address == dataEp.synchAddress
            if (fbEp == null && acc.uacVersion == 1) {
                fbEp = acc.endpoints.find { ep ->
                    (ep.attributes and 0x03) == 0x01 &&
                    (ep.address and 0x80) != 0 &&
                    ep.address == dataEp.synchAddress
                }
            }

            playback.add(UacAltSetting(
                interfaceNumber = key.first,
                alternateSetting = key.second,
                uacVersion = acc.uacVersion,
                terminalLink = acc.terminalLink,
                formatType = acc.formatType,
                pcm = acc.pcm,
                rawData = acc.rawData,
                channels = acc.channels,
                subslotBytes = acc.subslotBytes,
                bitResolution = acc.bitResolution,
                uac1Rates = acc.uac1Rates,
                uac1RateRange = acc.uac1RateRange,
                dataEndpoint = dataEp,
                feedbackEndpoint = fbEp,
                epAttributes = acc.epAttributes ?: 0,
            ))
        }

        if (playback.isEmpty()) return null

        return UacDevice(
            controlInterface = controlIface,
            controlVersion = controlVer!!,
            clocks = clocks,
            terminalClocks = terminalClocks,
            playback = playback,
            entitySources = entitySources,
            outputTerminals = outputTerminals,
            featureUnits = featureUnits,
        )
    }

    /** Channels (0 = master) of [fu] whose control [selector] (1 mute, 2 volume) can be read and set. */
    fun controlChannels(fu: UacFeatureUnit, uacVersion: Int, selector: Int): List<Int> =
        fu.controls.indices.filter { ch ->
            val m = fu.controls[ch]
            if (uacVersion == 2) (m ushr ((selector - 1) * 2)) and 0x03 == 0x03
            else (m ushr (selector - 1)) and 0x01 != 0
        }

    /**
     * The Feature Unit with a volume control on the path from the playback input terminal
     * [inputTerminal] (the alt setting's bTerminalLink) to an output terminal; the one closest to
     * the output terminal wins. Null if there is none (the DAC has no hardware volume).
     */
    fun volumeUnit(device: UacDevice, inputTerminal: Int): UacFeatureUnit? {
        for (ot in device.outputTerminals) {
            val path = pathTo(device, ot, inputTerminal, mutableSetOf()) ?: continue
            path.mapNotNull { device.featureUnits[it] }
                .firstOrNull { controlChannels(it, device.controlVersion, 2).isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    // Entity ids from [from] back to [target] (inclusive), or null if [target] is not upstream.
    private fun pathTo(device: UacDevice, from: Int, target: Int, seen: MutableSet<Int>): List<Int>? {
        if (from == target) return listOf(from)
        if (!seen.add(from)) return null
        for (src in device.entitySources[from].orEmpty()) {
            pathTo(device, src, target, seen)?.let { return listOf(from) + it }
        }
        return null
    }

    fun resolveClockSource(device: UacDevice, entityId: Int, selectorPin: (Int) -> Int = { 1 }): Int? {
        var current = entityId
        var steps = 0
        while (steps <= 8) {
            val entity = device.clocks[current] ?: return null
            when (entity.kind) {
                UacClockKind.SOURCE -> return entity.id
                UacClockKind.MULTIPLIER -> {
                    val next = entity.inputs.firstOrNull() ?: return null
                    current = next
                    steps++
                }
                UacClockKind.SELECTOR -> {
                    val pin = selectorPin(entity.id)
                    val idx = pin - 1
                    if (idx < 0 || idx >= entity.inputs.size) return null
                    current = entity.inputs[idx]
                    steps++
                }
            }
        }
        return null // loop guard exceeded
    }
}
