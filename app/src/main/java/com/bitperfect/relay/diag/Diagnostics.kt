package com.bitperfect.relay.diag

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import com.bitperfect.relay.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Text for the diagnostics popup (task 2026-10-24): the user copies it and pastes it into a
 * messenger. Nothing is sent from the app. Facts are recorded by the USB session and the service.
 */
object Diagnostics {
    /** DAC and stream facts of the latest USB start (multi-line). */
    @Volatile var dac: String = ""
    /** Latest relay counters ("USB rates" or "USB stop" line). */
    @Volatile var stats: String = ""
    /** Reason of the latest failed or aborted relay. */
    @Volatile var lastFailure: String = ""
    /** Raw USB descriptors of the latest DAC, hex. */
    @Volatile var descriptors: String = ""

    fun build(context: Context, relaying: Boolean): String {
        val sb = StringBuilder()
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        sb.appendLine("BitPerfect Relay diagnostics ${stamp(System.currentTimeMillis())}")
        sb.appendLine("App: ${pkg?.versionName ?: "?"} (${pkg?.longVersionCode ?: "?"}), engine ${Engine.version()}")
        sb.appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Relay: ${if (relaying) "running" else "stopped"}")
        val um = context.getSystemService(UsbManager::class.java)
        val usb = um?.deviceList?.values.orEmpty().map {
            "%s %04x:%04x".format(it.productName ?: it.deviceName, it.vendorId, it.productId)
        }
        sb.appendLine("USB devices now: ${usb.ifEmpty { listOf("none") }.joinToString(", ")}")
        sb.appendLine()
        sb.appendLine("[DAC at last start]")
        sb.appendLine(dac.ifEmpty { "no USB start in this app session" })
        sb.appendLine()
        sb.appendLine("[Relay stats${if (relaying) "" else ", last"}]")
        sb.appendLine(stats.ifEmpty { "none" })
        sb.appendLine()
        sb.appendLine("[Last failure]")
        sb.appendLine(lastFailure.ifEmpty { "none" })
        sb.appendLine()
        sb.appendLine("[Log, last ${AppLog.CAPACITY} app lines + native USB lines]")
        val lines = AppLog.snapshot().map { it.epochMs to "${it.level} ${it.text}" } +
            Engine.usbLog().lineSequence().mapNotNull { l ->
                val f = l.split("|", limit = 3)
                val t = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                t to "${f.getOrElse(1) { "?" }} usb: ${f.getOrElse(2) { "" }}"
            }
        lines.sortedBy { it.first }.takeLast(AppLog.CAPACITY).forEach { (t, s) -> sb.appendLine("${stamp(t)} $s") }
        if (descriptors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[USB descriptors]")
            sb.appendLine(descriptors)
        }
        return sb.toString()
    }

    private fun stamp(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
}
