package com.bitperfect.relay.diag

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug check (adb: `am start -S -n com.bitperfect.relay/.MainActivity --ei debug_sys_play_sec 5`):
 * play a quiet 1 kHz tone through Android's own audio path, pinned to the USB output with
 * setPreferredDevice, and log where it was routed and how many frames the DAC consumed.
 * Proves the system can use the DAC again after our relay stopped, without a person listening
 * and without changing the phone's Bluetooth or routing settings.
 */
object SystemPathCheck {
    private const val TAG = "BitPerfect"

    fun run(context: Context, seconds: Int) {
        Thread({
            val am = context.getSystemService(AudioManager::class.java)
            val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
            if (usb == null) {
                AppLog.e(TAG, "system path check: no USB audio output registered")
                return@Thread
            }
            val rate = 48000
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.setPreferredDevice(usb)
            val buf = ShortArray(rate / 10 * 2)
            var phase = 0.0
            track.play()
            val end = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < end) {
                for (i in 0 until buf.size / 2) {
                    val v = (0.03 * 32767 * sin(phase)).toInt().toShort() // about -30 dBFS
                    buf[2 * i] = v; buf[2 * i + 1] = v
                    phase += 2 * PI * 1000 / rate
                }
                val w = track.write(buf, 0, buf.size)
                if (w < 0) { AppLog.e(TAG, "system path check: write error $w"); break }
            }
            val routed = track.routedDevice
            val played = track.playbackHeadPosition
            AppLog.i(
                TAG, "system path check: routed to ${routed?.productName} type=${routed?.type} " +
                    "(usb=${routed?.id == usb.id}), frames played $played in $seconds s, underruns ${track.underrunCount}",
            )
            track.stop()
            track.release()
        }, "SystemPathCheck").start()
    }
}
