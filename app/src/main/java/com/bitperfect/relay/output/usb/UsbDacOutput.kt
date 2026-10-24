package com.bitperfect.relay.output.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import com.bitperfect.relay.Engine
import com.bitperfect.relay.data.AppSettings
import com.bitperfect.relay.diag.AppLog
import com.bitperfect.relay.diag.Diagnostics
import com.bitperfect.relay.service.RelayVolume
import com.bitperfect.relay.service.VolumeState

/** DAC facts for the UI (SPEC 8). 0 means unknown. */
data class UsbDacInfo(
    val uac2: Boolean,
    val bitDepth: Int,
    val maxRateHz: Int,
    val dsd: Boolean,
    val rates: List<Int>,
    /** A Feature Unit with volume sits on the playback path (from descriptors, no request sent). */
    val hwVolume: Boolean = false,
)

/**
 * One USB DAC streaming session (docs/ref/usb-dac.md, facts in docs/ref/uac.md).
 * Kotlin claims the interfaces, picks the alt setting and sets the rate; the native pump
 * (cpp/usb/usbplay.h) moves packed PCM. Owns [conn] and closes it in [stop].
 * start/stop on one control thread; write on the capture thread.
 */
class UsbDacOutput(
    private val context: Context,
    val device: UsbDevice,
    private val conn: UsbDeviceConnection,
) {

    var lastError = ""
        private set

    /** True after 5 s of all-zero captured audio, false again on the first non-zero frame. */
    @Volatile var captureSilent = false
        private set

    private var uac: UacDevice? = null
    @Volatile private var alt: UacAltSetting? = null
    private var selectedIface = -1
    private val claimed = mutableListOf<UsbInterface>()
    private var nativeOpen = false
    private var stopped = false
    private var packBuf = ByteArray(0)
    // Digital volume (Q16.16); unity = bit-exact passthrough. Only changed when the user enabled it.
    @Volatile private var gainQ16 = PcmPacker.UNITY_GAIN
    private var volumeAttached = false

    // Rate diagnostics: capture supply vs DAC consumption against the wall clock.
    private var diagT0 = 0L
    private var diagIn = 0L
    private var diagSent0 = 0L
    private var diagIn0 = 0L
    private var diagLast = 0L
    private var rateHz = 0
    private var zeroRun = 0L         // current run of all-zero frames
    private var zeroGaps = 0         // runs of >= 1 ms of exact digital silence inside the stream
    private var lastWriteNs = 0L
    private var maxWriteGapMs = 0.0  // longest pause between capture chunks since the last log
    private var channels = 2

    /**
     * Claim, configure and start streaming [rateHz] PCM. [digitalVolume]: the user allows digital
     * volume when the DAC has no hardware volume. On failure everything is released.
     */
    fun start(rateHz: Int, channels: Int = 2, digitalVolume: Boolean = false): Boolean {
        synchronized(lock) {
            if (active != null) return fail("another USB session is active")
            active = this
        }
        val raw = conn.rawDescriptors
        Diagnostics.descriptors = raw?.joinToString("") { "%02x".format(it) } ?: ""
        val d = raw?.let { UacDescriptors.parse(it) } ?: return fail("not a USB Audio playback device")
        uac = d
        if (!claim(d.controlInterface)) return fail("cannot claim the audio control interface")
        val ctl = control(d, conn)
        saveInfo(context, device, infoOf(d, d.playback.filter(::isStereoPcm).flatMap { ctl.supportedRates(it) }))
        // Diagnostic knob (adb shell setprop debug.bitperfect.alt N): force alternate setting N.
        val forcedAlt = debugProp("debug.bitperfect.alt")?.toIntOrNull()
        val a = (if (forcedAlt != null && forcedAlt > 0) d.playback.firstOrNull { it.alternateSetting == forcedAlt } else null)
            ?: ctl.chooseAlt(rateHz, channels)
            ?: return fail("DAC does not support $rateHz Hz $channels-channel PCM")
        if (!claim(a.interfaceNumber)) return fail("cannot claim the streaming interface")
        selectAlt(ctl, a, rateHz)?.let { return fail(it) }
        val volumeText = setupVolume(d, a, digitalVolume)

        if (Engine.usbOpen(conn.fileDescriptor) != 0) return fail(Engine.usbLastError())
        nativeOpen = true
        if (!startPump(a, rateHz, channels)) return fail(Engine.usbLastError())
        val s = Engine.usbStats()
        val data = a.dataEndpoint
        val fb = a.feedbackEndpoint
        val sync = when ((data.attributes ushr 2) and 0x03) { 1 -> "async"; 2 -> "adaptive"; 3 -> "sync"; else -> "none" }
        val fbText = if (fb != null) "explicit feedback ep 0x%02x".format(fb.address) else "no feedback endpoint"
        Diagnostics.dac = listOf(
            "%s VID 0x%04x PID 0x%04x, UAC%d, speed %d".format(device.productName, device.vendorId, device.productId, a.uacVersion, Engine.usbSpeed()),
            "if ${a.interfaceNumber} alt ${a.alternateSetting}, ${a.bitResolution} bit in ${a.subslotBytes} B, $channels ch, $rateHz Hz",
            "data ep 0x%02x maxPacket %d x%d interval %d, %s, %s".format(data.address, data.maxPacketBytes, 1 + data.extraTransactions, data.interval, sync, fbText),
            "queue: ${s[13]} transfers x ${s[14]} packets = %.1f ms in flight".format(s[12] / 1000.0),
            "volume: $volumeText",
        ).joinToString("\n")
        return true
    }

    /**
     * Change the stream rate while relaying (user request 2026-10-24): stop the pump, set the new
     * alt setting and rate, start the pump again. Interfaces stay claimed and the kernel driver stays
     * detached, so the system never takes the DAC back in between. [write] returns false meanwhile.
     * On failure everything is released, like a failed [start].
     */
    fun changeRate(rateHz: Int): Boolean {
        val d = uac ?: return false
        alt = null
        Engine.usbStop()
        val ctl = control(d, conn)
        val a = ctl.chooseAlt(rateHz, channels) ?: return fail("DAC does not support $rateHz Hz $channels-channel PCM")
        if (a.interfaceNumber != selectedIface) {
            findInterface(device, selectedIface, 0)?.let { conn.setInterface(it) }
            if (!claim(a.interfaceNumber)) return fail("cannot claim the streaming interface")
        }
        selectAlt(ctl, a, rateHz)?.let { return fail(it) }
        if (!startPump(a, rateHz, channels)) return fail(Engine.usbLastError())
        diagT0 = 0L
        zeroRun = 0L
        return true
    }

    /** Pump audio buffered on our side: ring plus transfers in flight (the UI's delay figure). */
    fun bufferedMs(): Int {
        if (alt == null || rateHz <= 0) return 0
        val s = Engine.usbStats()
        return (s[5] * 1000 / rateHz + s[12] / 1000).toInt()
    }

    /** Alt 0, rate, streaming alt, settle. Null on success, else the error. */
    private fun selectAlt(ctl: UacControl, a: UacAltSetting, rateHz: Int): String? {
        val intf = findInterface(device, a.interfaceNumber, a.alternateSetting)
            ?: return "alt setting ${a.alternateSetting} not found"
        // Order like UAPP and Linux: alt 0 first (the kernel
        // driver may have left it streaming), UAC2 clock rate and validity while at alt 0, then the
        // streaming alt last. UAC1 sets the rate on the endpoint, which needs the alt selected.
        findInterface(device, a.interfaceNumber, 0)?.let {
            if (!conn.setInterface(it)) AppLog.w(TAG, "USB: setInterface ${a.interfaceNumber}/0 failed")
        }
        selectedIface = a.interfaceNumber
        if (a.uacVersion == 2) ctl.setRate(a, rateHz)?.let { return it }
        if (!conn.setInterface(intf)) return "setInterface ${a.interfaceNumber}/${a.alternateSetting} failed"
        if (a.uacVersion != 2) ctl.setRate(a, rateHz)?.let { return it }
        // Let the DAC clock settle before streaming. [AI judgment] 200 ms; diagnostics only so far.
        Thread.sleep(SETTLE_MS)
        if (a.uacVersion == 2) {
            AppLog.i(TAG, "USB clock rate read back: ${ctl.clockSourceFor(a)?.let { ctl.readRate(it) }} Hz")
        }
        return null
    }

    /** Start the native pump for [a] at [rateHz]; the libusb handle must be open. */
    private fun startPump(a: UacAltSetting, rateHz: Int, channels: Int): Boolean {
        val data = a.dataEndpoint
        val fb = a.feedbackEndpoint
        val rc = Engine.usbStart(
            epOut = data.address,
            maxPacketBytes = data.maxPacketBytes * (1 + data.extraTransactions),
            bInterval = data.interval,
            epFb = fb?.address ?: 0,
            fbPacketBytes = fb?.let { it.maxPacketBytes * (1 + it.extraTransactions) } ?: 0,
            rateHz = rateHz,
            frameBytes = channels * a.subslotBytes,
            fillMax = (a.epAttributes and 0x80) != 0,
            primeMs = PRIME_MS,
            capacityMs = CAPACITY_MS,
        )
        if (rc != 0) return false
        this.rateHz = rateHz
        this.channels = channels
        alt = a
        val s = Engine.usbStats()
        AppLog.i(
            TAG, "USB start: ${device.productName} UAC${a.uacVersion} if ${a.interfaceNumber}/${a.alternateSetting} " +
                "${a.bitResolution}bit/${a.subslotBytes}B ${rateHz}Hz speed=${Engine.usbSpeed()} " +
                "ep=0x%02x fb=0x%02x inflight=%.1fms".format(data.address, fb?.address ?: 0, s[12] / 1000.0),
        )
        return true
    }

    /**
     * Hardware volume from the Feature Unit on the playback path, else digital volume if the user
     * enabled it. Saved level per DAC (VID:PID); the first time, the DAC's own level is kept.
     * Returns a one-line summary for diagnostics.
     */
    private fun setupVolume(d: UacDevice, a: UacAltSetting, digitalVolume: Boolean): String {
        val settings = AppSettings(context)
        val key = "${device.vendorId}:${device.productId}"
        volumeAttached = true // stop() clears RelayVolume from here on
        val fu = UacDescriptors.volumeUnit(d, a.terminalLink)
        if (fu != null) {
            val vol = UacVolume(d.controlVersion, d.controlInterface, fu, control(conn))
            val range = vol.readRange()
            val cur = vol.readVolume()
            AppLog.i(TAG, "USB volume: feature unit ${fu.id} channels ${vol.volumeChannels} range=$range cur=$cur mute=${vol.readMute()}")
            if (range != null && cur != null) {
                val saved = settings.dacVolume("$key:hw")
                val level = range.snap(saved ?: cur)
                if (level != cur && !vol.setVolume(level)) AppLog.w(TAG, "USB volume: restoring $level failed")
                if (level != cur) AppLog.i(TAG, "USB volume: restored $level, read back ${vol.readVolume()}")
                settings.setDacVolume("$key:hw", level)
                // [AI judgment] One +/- press or volume key = 2 dB, rounded to the DAC's resolution.
                val step = maxOf(range.res, 512 / range.res * range.res)
                RelayVolume.attach(
                    VolumeState(true, level, range.min, range.max, step),
                    snap = range::snap,
                    apply = { v ->
                        val ok = vol.setVolume(v)
                        AppLog.i(TAG, "USB volume: set $v (%.1f dB) ok=$ok, read back ${vol.readVolume()}".format(v / 256.0))
                        ok
                    },
                    persist = { settings.setDacVolume("$key:hw", it) },
                )
                return "hardware, feature unit ${fu.id}, %.1f..%.1f dB step %.2f dB, now %.1f dB".format(
                    range.min / 256.0, range.max / 256.0, range.res / 256.0, level / 256.0)
            }
            AppLog.w(TAG, "USB volume: feature unit ${fu.id} has volume but its range or level cannot be read")
        }
        if (fu == null) AppLog.i(TAG, "USB volume: no feature unit with volume on the playback path (terminal ${a.terminalLink})")
        if (!digitalVolume) {
            RelayVolume.setOfferDigital(true)
            return if (fu == null) "no hardware volume; digital volume off" else "hardware volume unreadable; digital volume off"
        }
        val pct = (settings.dacVolume("$key:dig") ?: 100).coerceIn(0, 100)
        gainQ16 = RelayVolume.digitalGainQ16(pct)
        RelayVolume.attach(
            VolumeState(false, pct, 0, 100, 5),
            snap = { it.coerceIn(0, 100) },
            apply = { p -> gainQ16 = RelayVolume.digitalGainQ16(p); AppLog.i(TAG, "digital volume: $p% gain=$gainQ16/65536"); true },
            persist = { settings.setDacVolume("$key:dig", it) },
        )
        AppLog.i(TAG, "digital volume on (not bit-perfect): $pct%")
        return "digital (not bit-perfect), $pct%"
    }

    /** Pack and queue [sampleCount] interleaved float samples. False if the stream failed. */
    fun write(samples: FloatArray, sampleCount: Int): Boolean {
        val a = alt ?: return false
        val bytes = sampleCount * a.subslotBytes
        if (packBuf.size < bytes) packBuf = ByteArray(bytes)
        PcmPacker.pack(samples, 0, sampleCount, a.subslotBytes, a.bitResolution, packBuf, 0, gainQ16)
        if (Engine.usbWrite(packBuf, bytes) < 0 || Engine.usbOk() != 0) {
            lastError = Engine.usbLastError()
            return false
        }
        scanCapture(samples, sampleCount)
        logRates(sampleCount / channels)
        return true
    }

    /** Stop streaming, give the device back to the kernel driver and close the connection. Idempotent. */
    fun stop() {
        if (stopped) return
        stopped = true
        if (volumeAttached) RelayVolume.detach()
        volumeAttached = false
        if (alt != null) {
            val s = Engine.usbStats()
            val line = "USB stop: underruns=${s[0]} silentFrames=${s[1]} droppedFrames=${s[2]} fb=${s[3]} errPackets=${s[4]} short=${s[10]} stallRestarts=${s[15]}"
            AppLog.i(TAG, line)
            Diagnostics.stats = (Diagnostics.stats.lineSequence().firstOrNull { it.startsWith("USB rates") }?.let { "$it\n" } ?: "") + line
        }
        alt = null
        if (nativeOpen) Engine.usbClose()
        nativeOpen = false
        if (selectedIface >= 0) {
            findInterface(device, selectedIface, 0)?.let {
                if (!conn.setInterface(it)) AppLog.w(TAG, "USB stop: setInterface $selectedIface/0 failed")
            }
        }
        releaseAll()
        uac?.let { reattach(conn, it.controlInterface) }
        conn.close()
        synchronized(lock) { if (active === this) active = null }
    }

    /** Diagnostics: count exact-zero gaps (>= 1 ms), the longest pause between capture chunks, and 5 s of silence. */
    private fun scanCapture(samples: FloatArray, sampleCount: Int) {
        val now = System.nanoTime()
        if (lastWriteNs != 0L) maxWriteGapMs = maxOf(maxWriteGapMs, (now - lastWriteNs) / 1e6)
        lastWriteNs = now
        var i = 0
        while (i + channels <= sampleCount) {
            var silent = true
            for (c in 0 until channels) if (samples[i + c] != 0f) silent = false
            if (silent) zeroRun++ else {
                if (zeroRun >= rateHz / 1000) zeroGaps++
                zeroRun = 0
            }
            i += channels
        }
        captureSilent = zeroRun >= rateHz.toLong() * SILENT_HINT_SEC
    }

    /** Every 5 s: capture and DAC frame rates over the session so far, ring level, counters. */
    private fun logRates(framesIn: Int) {
        val now = System.nanoTime()
        diagIn += framesIn
        if (diagT0 == 0L) {
            diagT0 = now; diagLast = now; diagIn0 = diagIn; diagSent0 = Engine.usbStats()[6]
            return
        }
        if (now - diagLast < 5_000_000_000L) return
        diagLast = now
        val s = Engine.usbStats()
        val sec = (now - diagT0) / 1e9
        val line = "USB rates: capture=%.1f Hz dac=%.1f Hz ring=%d ms inflight=%.1f ms underruns=%d silent=%d dropped=%d fb=%.5f raw=0x%08x len=%d shift=%d short=%d err=%d fbRejected=%d zeroGaps=%d maxChunkGap=%.1fms stallRestarts=%d".format(
            (diagIn - diagIn0) / sec, (s[6] - diagSent0) / sec, s[5] * 1000 / rateHz, s[12] / 1000.0, s[0], s[1], s[2], s[3] / 65536.0, s[7], s[8], s[9], s[10], s[4], s[11], zeroGaps, maxWriteGapMs, s[15],
        )
        AppLog.i(TAG, line)
        Diagnostics.stats = "%s (after %.0f s)".format(line, sec)
        maxWriteGapMs = 0.0
    }

    private fun claim(id: Int): Boolean {
        val intf = findInterface(device, id, null) ?: return false
        if (!conn.claimInterface(intf, true)) return false
        claimed += intf
        return true
    }

    private fun releaseAll() {
        for (intf in claimed.asReversed()) {
            if (!conn.releaseInterface(intf)) AppLog.w(TAG, "USB: releaseInterface ${intf.id} failed")
        }
        claimed.clear()
    }

    private fun fail(msg: String): Boolean {
        lastError = msg
        AppLog.e(TAG, "USB start failed: $msg")
        stop()
        return false
    }

    companion object {
        private const val TAG = "BitPerfect"
        // [AI judgment] 200 ms cushion before real data (start and after an underrun), 1 s ring.
        // Without resampling the capture and DAC clocks drift apart; the cushion only delays the gap.
        // The native pump raises the ring if it is smaller than prime + 2 x in-flight time.
        private const val PRIME_MS = 200
        private const val CAPACITY_MS = 1000
        private const val SETTLE_MS = 200L
        private const val SILENT_HINT_SEC = 5

        private val lock = Any()
        private var active: UsbDacOutput? = null

        /**
         * DAC facts for the UI without touching the device: the kernel driver stays attached,
         * so opening the app never disturbs system audio. UAC2 rates need a control request,
         * which needs the interface claimed; they come from the cache written by [start], else
         * from the rates the system reports for the USB output, else are unknown.
         */
        fun probe(context: Context, device: UsbDevice, conn: UsbDeviceConnection): UsbDacInfo? {
            val d = parse(conn) ?: return null
            loadInfo(context, device)?.let { return it.copy(hwVolume = hasHwVolume(d)) }
            val stereo = d.playback.filter(::isStereoPcm)
            val rates = if (stereo.all { it.uacVersion == 1 }) {
                val ctl = control(d, conn) // UAC1: rates come from descriptors, no request is sent
                stereo.flatMap { ctl.supportedRates(it) }
            } else {
                context.getSystemService(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .filter { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                    .flatMap { it.sampleRates.toList() }
            }
            return infoOf(d, rates)
        }

        private fun isStereoPcm(a: UacAltSetting) = a.pcm && a.formatType == 1 && a.channels == 2

        private fun hasHwVolume(d: UacDevice) =
            d.playback.filter(::isStereoPcm).any { UacDescriptors.volumeUnit(d, it.terminalLink) != null }

        private fun infoOf(d: UacDevice, rates: List<Int>): UsbDacInfo {
            val r = rates.distinct().sorted()
            return UsbDacInfo(
                uac2 = d.controlVersion == 2,
                bitDepth = d.playback.filter(::isStereoPcm).maxOfOrNull { it.bitResolution } ?: 0,
                maxRateHz = r.maxOrNull() ?: 0,
                // [AI judgment] A UAC2 raw-data alt setting is how DACs expose native DSD.
                dsd = d.playback.any { it.rawData },
                rates = r,
                hwVolume = hasHwVolume(d),
            )
        }

        private const val CACHE = "dac_info"
        private fun cacheKey(device: UsbDevice) =
            "${device.vendorId}:${device.productId}:${runCatching { device.serialNumber }.getOrNull() ?: ""}"

        private fun saveInfo(context: Context, device: UsbDevice, info: UsbDacInfo) {
            if (info.rates.isEmpty()) return
            context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                .putString(cacheKey(device), "${info.uac2}|${info.bitDepth}|${info.dsd}|${info.rates.joinToString(",")}")
                .apply()
        }

        private fun loadInfo(context: Context, device: UsbDevice): UsbDacInfo? {
            val v = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).getString(cacheKey(device), null)
                ?: return null
            val f = v.split("|")
            if (f.size != 4) return null
            val rates = f[3].split(",").mapNotNull { it.toIntOrNull() }
            return UsbDacInfo(f[0].toBoolean(), f[1].toIntOrNull() ?: 0, rates.maxOrNull() ?: 0, f[2].toBoolean(), rates)
        }

        /** Reads a system property through getprop (diagnostics only); null if unset. */
        private fun debugProp(name: String): String? = try {
            ProcessBuilder("getprop", name).start().inputStream.bufferedReader().readLine()?.trim()?.ifEmpty { null }
        } catch (e: java.io.IOException) {
            AppLog.w(TAG, "getprop $name failed: ${e.message}")
            null
        }

        private fun parse(conn: UsbDeviceConnection): UacDevice? =
            conn.rawDescriptors?.let { UacDescriptors.parse(it) }

        private fun control(conn: UsbDeviceConnection) = ControlIo { t, r, v, i, b, l ->
            conn.controlTransfer(t, r, v, i, b, l, CONTROL_TIMEOUT_MS)
        }

        private fun control(d: UacDevice, conn: UsbDeviceConnection) = UacControl(d, control(conn))

        private fun reattach(conn: UsbDeviceConnection, iface: Int) {
            val rc = Engine.usbReattach(conn.fileDescriptor, iface)
            // -EBUSY (-16): the kernel already re-bound its driver after the release (seen on Samsung).
            if (rc == -16) AppLog.i(TAG, "USB: kernel driver already re-bound on interface $iface")
            else if (rc < 0) AppLog.w(TAG, "USB: kernel driver rebind on interface $iface failed: $rc")
        }

        /** Interface [id] with alternate setting [alt], or any alt setting of [id] if [alt] is null. */
        private fun findInterface(device: UsbDevice, id: Int, alt: Int?): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.id == id && (alt == null || it.alternateSetting == alt) }

        private const val CONTROL_TIMEOUT_MS = 1000
    }
}
