package com.bitperfect.relay.output.usb

/** Volume range of a Feature Unit in 1/256 dB (UAC1 5.2.2.4.3.2, UAC2 5.2.5.7.2). */
data class VolumeRange(val min: Int, val max: Int, val res: Int) {
    /** [v] clamped to the range and snapped to a whole resolution step above min. */
    fun snap(v: Int): Int {
        val c = v.coerceIn(min, max)
        return (min + (c - min + res / 2) / res * res).coerceAtMost(max)
    }
}

/**
 * Hardware volume and mute of a USB DAC Feature Unit. Request codes and value layout as in
 * Linux sound/usb/mixer.c (get_ctl_value_v1/_v2, snd_usb_mixer_set_ctl_value) and
 * include/uapi/linux/usb/audio.h, include/linux/usb/audio-v2.h:
 * - wValue = control selector << 8 | channel (0 master), wIndex = unit id << 8 | AC interface,
 *   class request to the interface (IN 0xA1, OUT 0x21).
 * - UAC1: GET_CUR 0x81, GET_MIN 0x82, GET_MAX 0x83, GET_RES 0x84, SET_CUR 0x01.
 * - UAC2: CUR 0x01 (get and set), RANGE 0x02; like Linux only the first sub-range is read
 *   (2-byte count, then MIN, MAX, RES of 2 bytes each).
 * - Volume: signed 16 bit, 1/256 dB (0x8000 = silence in UAC1). Mute: 1 byte.
 * Control transfers on endpoint 0 are safe while the iso stream runs (separate endpoint).
 */
class UacVolume(
    private val uacVersion: Int,
    private val controlInterface: Int,
    val unit: UacFeatureUnit,
    private val io: ControlIo,
) {
    private companion object {
        const val IN = 0xA1
        const val OUT = 0x21
        const val CS_MUTE = 1
        const val CS_VOLUME = 2
        const val UAC1_SET_CUR = 0x01
        const val UAC1_GET_CUR = 0x81
        const val UAC1_GET_MIN = 0x82
        const val UAC1_GET_MAX = 0x83
        const val UAC1_GET_RES = 0x84
        const val UAC2_CUR = 0x01
        const val UAC2_RANGE = 0x02
    }

    /** Volume is set on the master channel if it has one, else on every channel that has it. */
    val volumeChannels: List<Int> = UacDescriptors.controlChannels(unit, uacVersion, CS_VOLUME)
        .let { if (0 in it) listOf(0) else it }
    val muteChannels: List<Int> = UacDescriptors.controlChannels(unit, uacVersion, CS_MUTE)
        .let { if (0 in it) listOf(0) else it }

    private fun index() = (unit.id shl 8) or controlInterface
    private fun wValue(cs: Int, ch: Int) = (cs shl 8) or ch

    private fun s16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)).toShort().toInt()

    private fun get16(request: Int, ch: Int): Int? {
        val buf = ByteArray(2)
        return if (io.transfer(IN, request, wValue(CS_VOLUME, ch), index(), buf, 2) >= 2) s16(buf, 0) else null
    }

    /** Range of the first volume channel, null if it cannot be read or is empty. */
    fun readRange(): VolumeRange? {
        val ch = volumeChannels.firstOrNull() ?: return null
        val (min, max, res) = if (uacVersion == 2) {
            val buf = ByteArray(8)
            if (io.transfer(IN, UAC2_RANGE, wValue(CS_VOLUME, ch), index(), buf, 8) < 8) return null
            Triple(s16(buf, 2), s16(buf, 4), s16(buf, 6))
        } else {
            Triple(get16(UAC1_GET_MIN, ch) ?: return null, get16(UAC1_GET_MAX, ch) ?: return null,
                get16(UAC1_GET_RES, ch) ?: return null)
        }
        if (min >= max) return null
        return VolumeRange(min, max, if (res > 0) res else 1) // Linux also treats res 0 as 1
    }

    /** Current volume (1/256 dB) of the first volume channel, null on failure. */
    fun readVolume(): Int? {
        val ch = volumeChannels.firstOrNull() ?: return null
        return get16(if (uacVersion == 2) UAC2_CUR else UAC1_GET_CUR, ch)
    }

    /** Set [v] (1/256 dB) on all volume channels. False if any request failed. */
    fun setVolume(v: Int): Boolean {
        if (volumeChannels.isEmpty()) return false
        val buf = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        val req = if (uacVersion == 2) UAC2_CUR else UAC1_SET_CUR
        return volumeChannels.all { io.transfer(OUT, req, wValue(CS_VOLUME, it), index(), buf, 2) >= 0 }
    }

    /** Mute state of the first mute channel, null if there is none or the read failed. */
    fun readMute(): Boolean? {
        val ch = muteChannels.firstOrNull() ?: return null
        val buf = ByteArray(1)
        val req = if (uacVersion == 2) UAC2_CUR else UAC1_GET_CUR
        return if (io.transfer(IN, req, wValue(CS_MUTE, ch), index(), buf, 1) >= 1) buf[0].toInt() != 0 else null
    }

    /** Set mute on all mute channels. False if there is none or a request failed. */
    fun setMute(on: Boolean): Boolean {
        if (muteChannels.isEmpty()) return false
        val buf = byteArrayOf(if (on) 1 else 0)
        val req = if (uacVersion == 2) UAC2_CUR else UAC1_SET_CUR
        return muteChannels.all { io.transfer(OUT, req, wValue(CS_MUTE, it), index(), buf, 1) >= 0 }
    }
}
