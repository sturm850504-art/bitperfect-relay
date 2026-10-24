package com.bitperfect.relay

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.bitperfect.relay.diag.AppLog
import com.bitperfect.relay.diag.SystemPathCheck
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitperfect.relay.data.LibraryManager
import com.bitperfect.relay.service.MediaMute
import com.bitperfect.relay.service.RelayService
import com.bitperfect.relay.ui.MainScreen
import com.bitperfect.relay.ui.SettingsScreen
import com.bitperfect.relay.ui.theme.BitPerfectTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 엔진 초기화
        Engine.init(applicationInfo.nativeLibraryDir)

        // Undo a music mute left by a relay whose process died (MediaMute).
        MediaMute.restoreAfterCrash(this)

        // Stage 9: re-apply persisted library selections (dlopen swap, best effort).
        // Nothing is streaming at startup, so the main thread is safe to use here.
        LibraryManager(this).let { lm ->
            lm.selected.value.forEach { (key, version) ->
                if (lm.applySelection(key, version) != 0) {
                    AppLog.w(TAG, "lib re-apply failed: $key/$version — ${Engine.libLastError()}")
                }
            }
        }

        AppLog.i(TAG, "app started")

        // Diagnostic: does the platform offer bit-perfect mixer attributes for a USB output (API 34)?
        if (debuggable && intent?.getBooleanExtra("debug_mixer", false) == true) {
            val am = getSystemService(android.media.AudioManager::class.java)
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE }
                .forEach { d ->
                    val attrs = am.getSupportedMixerAttributes(d)
                    AppLog.i(TAG, "mixer: ${d.productName} type=${d.type} rates=${d.sampleRates.toList()} supported=${attrs.size}")
                    attrs.forEach { AppLog.i(TAG, "mixer:   behavior=${it.mixerBehavior} format=${it.format}") }
                }
            AppLog.i(TAG, "mixer: check done")
        }

        handleDebugExtras(intent)

        setContent {
            BitPerfectTheme {
                var showSettings by remember { mutableStateOf(false) }
                // System back: from settings return to home; from home finish.
                BackHandler(enabled = showSettings) { showSettings = false }
                // targetSdk 35+ draws edge to edge; keep content out of the status and navigation bars
                // (the title overlapped the status bar on the API 36 emulator).
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.safeDrawingPadding(),
                ) {
                    if (showSettings) {
                        SettingsScreen(onBack = { showSettings = false })
                    } else {
                        MainScreen(onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }

    // adb debug extras also arrive here when the activity is already running (single top task).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugExtras(intent)
    }

    // Debug builds only: the launcher activity is exported, so in a release build any app could
    // send these extras (test tone, stop). [AI judgment, user agreed 2026-10-24]
    private val debuggable: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun handleDebugExtras(intent: Intent?) {
        if (intent == null || !debuggable) return
        // Diagnostic: stop the relay from adb (am start ... --ez debug_stop true).
        if (intent.getBooleanExtra("debug_stop", false) == true) RelayService.requestStop(this)

        // Diagnostic: USB test tone without capture, through the relay's USB sequence (RelayService).
        intent.getIntExtra("debug_tone_rate", 0)?.takeIf { it > 0 }?.let { rate -> startTestTone(rate, intent.getIntExtra("debug_tone_sec", 30)) }
        // Diagnostic: volume-key routing to our media session without USB (RelayService.debugVolumeKeys).
        intent.getIntExtra("debug_volume_keys_sec", 0).takeIf { it > 0 }?.let {
            startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_DEBUG_VOLUME_KEYS).putExtra(RelayService.EXTRA_SECONDS, it))
        }
        // Diagnostic: can the system itself play on the USB DAC (after a relay stopped)? See SystemPathCheck.
        intent.getIntExtra("debug_sys_play_sec", 0)?.takeIf { it > 0 }?.let { SystemPathCheck.run(this, it) }
    }

    /** Debug tone on the first USB audio device with permission (the relay's own start sequence). */
    private fun startTestTone(rate: Int, seconds: Int) {
        val um = getSystemService(UsbManager::class.java)
        val dev = um.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 1 && d.getInterface(it).interfaceSubclass == 2 }
        }
        if (dev == null || !um.hasPermission(dev)) AppLog.e(TAG, "test tone: no USB DAC with permission")
        startService(
            Intent(this, RelayService::class.java)
                .setAction(RelayService.ACTION_START)
                .putExtra(RelayService.EXTRA_TONE_SEC, seconds)
                .putExtra(RelayService.EXTRA_SAMPLE_RATE, rate)
                .putExtra(RelayService.EXTRA_OUTPUT_ID, dev?.takeIf { um.hasPermission(it) }?.let { RelayService.usbId(it) }),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Engine.destroy()
        AppLog.i(TAG, "app stopped")
    }

    companion object {
        const val TAG = "BitPerfect"
    }
}
