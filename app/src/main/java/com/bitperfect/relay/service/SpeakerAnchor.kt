package com.bitperfect.relay.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.bitperfect.relay.diag.AppLog

/**
 * Keeps the system media output on the built-in speaker while the app owns the USB DAC.
 *
 * After the app claims the DAC, Android still lists it as a USB audio output (it only removes
 * it on a physical unplug) and keeps routing media there; that output runs off its real-time
 * pace and the playback capture gets silence gaps. UAPP solves this with a silent media track
 * whose preferred device is the speaker: the explicitly routed client moves the shared media
 * output, and the target app's tracks follow (observed on the device 2026-10-11). The speaker path is then muted by [MediaMute].
 *
 * The pin does not always hold: on the Galaxy S25 FE a system sound on the USB route pulls the
 * media output back to USB when it ends (device log 2026-10-25). [onSpeaker] and the route
 * listener let RelayService notice that and re-pin.
 */
class SpeakerAnchor(context: Context) {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null
    private var routeListener: AudioRouting.OnRoutingChangedListener? = null

    /** [onRouteChange] runs on the main thread with "the anchor plays on the speaker" after each route change. */
    fun start(onRouteChange: ((Boolean) -> Unit)? = null) {
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
        if (onRouteChange != null) {
            val l = AudioRouting.OnRoutingChangedListener { r ->
                onRouteChange(r.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            }
            track.addOnRoutingChangedListener(l, Handler(Looper.getMainLooper()))
            routeListener = l
        }
        this.track = track
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
                routeListener?.let { track.removeOnRoutingChangedListener(it) }
                this.track = null
                track.stop()
                track.release()
            }
        }, "SpeakerAnchor").apply { start() }
        AppLog.i(TAG, "SpeakerAnchor: media output pinned to the speaker")
    }

    fun isRunning(): Boolean = running

    /** True while the anchor (and so the shared media output) is routed to the built-in speaker. */
    fun onSpeaker(): Boolean = track?.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

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
