package com.bitperfect.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.bitperfect.relay.diag.AppLog

/**
 * Silences the original playback on the phone's own outputs while relaying to the USB DAC,
 * the way UAPP does in its capture mode: save the music volume,
 * set it to 2 and mute the music stream. The playback-capture copy is unaffected because
 * its remote-submix output has fixed unit gain (AOSP SwAudioOutputDescriptor::isFixedVolume).
 * Like UAPP's default, volume key presses are ignored while engaged: the mute is re-applied.
 * Re-apply triggers: Settings.System changes (volume index persisted, about 0.5 s late) and,
 * since 2026-10-24, AudioService's VOLUME_CHANGED_ACTION / STREAM_MUTE_CHANGED_ACTION broadcasts
 * (AOSP AudioService.sendBroadcastToAll; hidden action strings). The broadcasts also catch an
 * unmute that leaves the index unchanged (raise then lower), which Settings never reports
 * (device test 2026-10-24: the stream stayed unmuted). Volume keys normally reach RelayService's
 * volume session instead and never touch the music stream (docs/ref/volume.md).
 *
 * The saved volume is persisted, so a crash during a relay is undone on the next app start
 * ([restoreAfterCrash]).
 */
object MediaMute {
    private const val TAG = "BitPerfect"
    private const val PREFS = "media_mute"
    private const val KEY_SAVED = "saved_music_volume"
    private const val KEY_PENDING_USB = "saved_level_pending_usb"
    private const val MUTED_VOLUME = 2 // UAPP value

    @Volatile private var engaged = false
    private var observer: ContentObserver? = null
    private var receiver: BroadcastReceiver? = null
    private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val ACTION_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
    private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    @Synchronized
    fun engage(context: Context) {
        if (engaged) return
        val app = context.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A level left pending by a detach belongs to the USB device, whose index is still the muted 2.
        val pending = prefs.getBoolean(KEY_PENDING_USB, false) && prefs.getInt(KEY_SAVED, -1) >= 0
        val saved = if (pending) prefs.getInt(KEY_SAVED, -1) else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        prefs.edit().putInt(KEY_SAVED, saved).remove(KEY_PENDING_USB).commit()
        engaged = true
        apply(am)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reapplyIfChanged(am, "settings")
        }
        // UAPP observes Settings.System for volume changes in the same way.
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
        observer = obs
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val type = intent.getIntExtra(EXTRA_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                if (type == AudioManager.STREAM_MUSIC) reapplyIfChanged(am, intent.action ?: "?")
            }
        }
        // System broadcasts reach a NOT_EXPORTED receiver (only other apps are excluded).
        app.registerReceiver(
            rcv, IntentFilter().apply { addAction(ACTION_VOLUME_CHANGED); addAction(ACTION_MUTE_CHANGED) },
            Context.RECEIVER_NOT_EXPORTED,
        )
        receiver = rcv
        AppLog.i(TAG, "MediaMute: engaged (music volume $saved saved)")
    }

    /**
     * [deviceGone]: the USB DAC was unplugged. The music index is per device and the index set at
     * engage belongs to that DAC; setting it now would land on the speaker. So only unmute, and keep
     * the saved level pending for the DAC (applied at the next engage or app start with it attached).
     */
    @Synchronized
    fun release(context: Context, deviceGone: Boolean = false) {
        if (!engaged) return
        engaged = false
        val app = context.applicationContext
        observer?.let { app.contentResolver.unregisterContentObserver(it) }
        observer = null
        receiver?.let { app.unregisterReceiver(it) }
        receiver = null
        if (deviceGone) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING_USB, true).commit()
            app.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            AppLog.i(TAG, "MediaMute: released after detach (unmuted; USB level kept pending)")
        } else {
            restore(app)
        }
    }

    /**
     * Call at app start. Undoes a mute left by a process that died while relaying, and applies a
     * level left pending by a detach once the media route is on a USB device again.
     */
    @Synchronized
    fun restoreAfterCrash(context: Context) {
        if (engaged) return
        val app = context.applicationContext
        if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING_USB, false)) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            val onUsb = app.getSystemService(AudioManager::class.java).getAudioDevicesForAttributes(media).any {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
            if (!onUsb) return
        }
        restore(app)
    }

    // Only the mute matters while engaged. getStreamVolume reports 0 for a muted stream, and
    // setStreamVolume unmutes (AOSP AudioService), so re-apply is a plain mute, never an index change.
    private fun reapplyIfChanged(am: AudioManager, source: String) {
        if (!engaged || am.isStreamMute(AudioManager.STREAM_MUSIC)) return
        AppLog.w(TAG, "MediaMute: music stream unmuted ($source), muting again")
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    private fun apply(am: AudioManager) {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, MUTED_VOLUME, 0)
        // Same call AudioManager.setStreamMute(true) makes internally (AOSP AudioManager.java).
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    private fun restore(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_SAVED, -1)
        if (saved < 0) return
        val am = app.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        prefs.edit().remove(KEY_SAVED).remove(KEY_PENDING_USB).commit()
        AppLog.i(TAG, "MediaMute: released (music volume $saved restored)")
    }
}
