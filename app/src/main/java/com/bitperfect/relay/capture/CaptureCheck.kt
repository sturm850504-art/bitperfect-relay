package com.bitperfect.relay.capture

import android.content.Context
import android.content.pm.PackageManager
import com.bitperfect.relay.diag.AppLog

/**
 * Whether another app's playback is known to be uncapturable (ui-spec card 1, "캡처 차단").
 *
 * Two sources:
 * - The app's manifest `android:allowAudioPlaybackCapture`. The SDK exposes no public getter on
 *   ApplicationInfo (android-36 stub, javap), so the attribute is read from the app's compiled
 *   manifest. Default when absent: allowed for targetSdk >= 29, not allowed below
 *   (developer.android.com/media/platform/av-capture).
 * - A small list of apps that allow capture in the manifest but block it at runtime
 *   (docs/ref/target-apps.md: Spotify sets the track flag 0x400).
 */
object CaptureCheck {

    /** Packages seen blocking capture at runtime on a real device (target-apps.md). */
    val KNOWN_RUNTIME_BLOCKERS: Set<String> = setOf("com.spotify.music")

    private const val MAX_COOKIES = 16

    /** allowAudioPlaybackCapture of this manifest, or null if it belongs to another package. */
    private fun readApplication(p: android.content.res.XmlResourceParser, packageName: String, default: Boolean): Boolean? {
        var event = p.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "manifest" &&
                p.getAttributeValue(null, "package") != packageName
            ) return null
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "application") {
                // Match by resource id, not by name: compiled manifests may strip attribute names.
                for (i in 0 until p.attributeCount) {
                    if (p.getAttributeNameResource(i) == android.R.attr.allowAudioPlaybackCapture) {
                        return p.getAttributeBooleanValue(i, default)
                    }
                }
                return default
            }
            event = p.next()
        }
        return null
    }

    /** Pure decision, unit-tested: [manifestAllows] null means the manifest could not be read. */
    fun isBlocked(packageName: String, manifestAllows: Boolean?, knownBlockers: Set<String> = KNOWN_RUNTIME_BLOCKERS): Boolean =
        packageName in knownBlockers || manifestAllows == false

    /** True when capture is known to fail for [packageName]. Reads the package manifest (call off the main thread). */
    fun isBlocked(context: Context, packageName: String): Boolean =
        isBlocked(packageName, manifestAllows(context, packageName))

    /** The manifest's allowAudioPlaybackCapture with the platform default applied; null if unreadable. */
    fun manifestAllows(context: Context, packageName: String): Boolean? {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            val default = info.targetSdkVersion >= 29
            val pkgContext = context.createPackageContext(packageName, 0)
            // The cookie-less lookup can return a shared library's manifest (Chrome: trichromelibrary,
            // seen on the API 36 emulator), so try each loaded APK and keep the one for this package.
            for (cookie in 1..MAX_COOKIES) {
                val parser = try {
                    pkgContext.assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
                } catch (e: java.io.IOException) {
                    break // no APK at this cookie: all loaded APKs were tried
                }
                parser.use { p -> readApplication(p, packageName, default)?.let { return it } }
            }
            AppLog.w("BitPerfect", "capture check: own manifest of $packageName not found")
            null
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            AppLog.w("BitPerfect", "capture check: manifest of $packageName unreadable: $e")
            null
        }
    }
}
