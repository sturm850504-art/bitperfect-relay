package com.bitperfect.relay.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.bitperfect.relay.diag.AppLog

/**
 * Keeps the system media output on the built-in speaker while the app owns the USB DAC.
 *
 * After the app claims the DAC, Android still lists it as a USB audio output (it only removes
 * it on a physical unplug) and keeps routing media there; that output runs off its real-time
 * pace and the playback capture gets silence gaps. UAPP solves this with a silent media track
 * whose preferred device is the speaker: the explicitly routed client moves the shared media
 * output, and the target app's tracks follow (observed on the device 2026-10-11). The speaker path is then muted by [MediaMute].
 */
class SpeakerAnchor(context: Context) {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        val speaker = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            AppLog.w(TAG, "SpeakerAnchor: no built-in speaker")
            return
        }
        val rate = 48000
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            AppLog.w(TAG, "SpeakerAnchor: getMinBufferSize failed ($minBuf)")
            return
        }
        // Same attributes as UAPP's silence track: media usage, music content.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (!track.setPreferredDevice(speaker)) AppLog.w(TAG, "SpeakerAnchor: setPreferredDevice failed")
        running = true
        thread = Thread({
            val silence = ByteArray(minBuf)
            try {
                track.play()
                while (running) {
                    val n = track.write(silence, 0, silence.size)
                    if (n < 0) {
                        AppLog.e(TAG, "SpeakerAnchor: write failed ($n)")
                        break
                    }
                }
            } finally {
                track.stop()
                track.release()
            }
        }, "SpeakerAnchor").apply { start() }
        AppLog.i(TAG, "SpeakerAnchor: media output pinned to the speaker")
    }

    fun isRunning(): Boolean = running

    fun stop() {
        if (!running) return
        running = false
        thread?.join(1000)
        thread = null
        AppLog.i(TAG, "SpeakerAnchor: released")
    }

    private companion object {
        const val TAG = "BitPerfect"
    }
}
