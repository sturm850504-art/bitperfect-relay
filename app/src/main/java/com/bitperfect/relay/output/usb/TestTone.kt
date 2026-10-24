package com.bitperfect.relay.output.usb

import com.bitperfect.relay.Engine
import kotlin.math.PI
import kotlin.math.sin

/**
 * Diagnostic source instead of capture: a 1 kHz sine at -20 dBFS, stereo.
 * Started from adb: `am start -n com.bitperfect.relay/.MainActivity --ei debug_tone_rate 48000
 * --ei debug_tone_sec 30`. RelayService feeds it through the same USB start and stop sequence as
 * a relay (focus, mute, speaker anchor, claim, stop and re-bind), so it cannot leave the DAC in a
 * bad state (task 2026-10-24). [read] paces itself on the USB ring (about 300 ms), so the DAC's
 * feedback alone shows whether the DAC receives what is sent.
 */
class TestTone(private val rateHz: Int, private val seconds: Int) {
    private var end = 0L // set on the first read, so the USB start time does not count
    private val step = 2 * PI * 1000.0 / rateHz
    private var phase = 0.0

    /** Fill [buf] with up to 10 ms of interleaved stereo samples; 0 when the tone is over. */
    fun read(buf: FloatArray): Int {
        if (end == 0L) end = System.nanoTime() + seconds * 1_000_000_000L
        while (Engine.usbStats()[5] > rateHz * 3 / 10) {
            if (System.nanoTime() >= end) return 0
            Thread.sleep(5)
        }
        if (System.nanoTime() >= end) return 0
        val frames = minOf(rateHz / 100, buf.size / 2)
        for (i in 0 until frames) {
            val v = (0.1 * sin(phase)).toFloat()
            buf[2 * i] = v; buf[2 * i + 1] = v
            phase += step
            if (phase > 2 * PI) phase -= 2 * PI
        }
        return frames * 2
    }
}
