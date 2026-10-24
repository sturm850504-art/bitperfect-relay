package com.bitperfect.relay.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One app the user added to the list (persisted). */
data class AppEntry(
    val packageName: String,
    val displayName: String,
    val sampleRate: Int = 44100,
)

/** Selected output device (persisted so a restart can resume immediately). */
data class OutputSelection(
    val type: String,   // "usb" (Bluetooth output removed 2026-10-11)
    val id: String,     // usb: "vendorId:productId:serial"; bt: device name
    val name: String,
)

/**
 * Stage 7 persistence: app list, per-app sample rate, selected app, selected output.
 * Survives app restarts. Backed by SharedPreferences (platform API) exposed as
 * reactive StateFlow so the UI updates without manual refresh.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow(loadApps())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _selectedApp = MutableStateFlow(prefs.getString(KEY_SELECTED_APP, null))
    val selectedApp: StateFlow<String?> = _selectedApp

    private val _selectedOutput = MutableStateFlow(loadOutput())
    val selectedOutput: StateFlow<OutputSelection?> = _selectedOutput

    // Digital volume (not bit-perfect; user allowed it 2026-10-24 only for DACs without hardware volume).
    private val _digitalVolume = MutableStateFlow(prefs.getBoolean(KEY_DIGITAL_VOLUME, false))
    val digitalVolume: StateFlow<Boolean> = _digitalVolume

    fun setDigitalVolume(on: Boolean) {
        _digitalVolume.value = on
        prefs.edit().putBoolean(KEY_DIGITAL_VOLUME, on).apply()
    }

    /** The battery-optimization exemption was offered once (first start); never asked again. */
    val batteryAsked: Boolean get() = prefs.getBoolean(KEY_BATTERY_ASKED, false)

    fun setBatteryAsked() {
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
    }

    /** Visualizer form and color preset shown while relaying (indexes; see ui/Visualizer.kt). */
    var vizMode: Int
        get() = prefs.getInt("viz_mode", 0)
        set(v) { prefs.edit().putInt("viz_mode", v).apply() }
    var vizPreset: Int
        get() = prefs.getInt("viz_preset", 0)
        set(v) { prefs.edit().putInt("viz_preset", v).apply() }
    /** User color preset: ARGB ints in VizPreset.slots() order; null until edited. */
    var vizCustom: List<Int>?
        get() = prefs.getString("viz_custom", null)?.split(",")?.mapNotNull { it.toLongOrNull()?.toInt() }
        set(v) { prefs.edit().putString("viz_custom", v?.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply() }
    /** VU needle: time to 99% of a step, ms. */
    var vizNeedleMs: Int
        get() = prefs.getInt("viz_needle_ms", 600)
        set(v) { prefs.edit().putInt("viz_needle_ms", v).apply() }
    /** Extra view delay on top of the relay's buffer, ms (Bluetooth or DAC delay after it). */
    var vizSyncMs: Int
        get() = prefs.getInt("viz_sync_ms", 0)
        set(v) { prefs.edit().putInt("viz_sync_ms", v).apply() }

    /** Saved volume for a DAC, key "vid:pid:hw" (1/256 dB) or "vid:pid:dig" (percent); null if never saved. */
    fun dacVolume(key: String): Int? = if (prefs.contains(KEY_VOLUME + key)) prefs.getInt(KEY_VOLUME + key, 0) else null

    fun setDacVolume(key: String, value: Int) {
        prefs.edit().putInt(KEY_VOLUME + key, value).apply()
    }

    // ── Mutations (sync: persist then publish) ─────────────────────────
    fun addApp(entry: AppEntry) {
        val next = _apps.value.filter { it.packageName != entry.packageName } + entry
        saveApps(next)
        _apps.value = next
    }

    fun removeApp(packageName: String) {
        _apps.value = _apps.value.filter { it.packageName != packageName }
        saveApps(_apps.value)
        if (_selectedApp.value == packageName) setSelectedApp(null)
    }

    fun setAppRate(packageName: String, rate: Int) {
        _apps.value = _apps.value.map {
            if (it.packageName == packageName) it.copy(sampleRate = rate) else it
        }
        saveApps(_apps.value)
    }

    fun setSelectedApp(packageName: String?) {
        _selectedApp.value = packageName
        val e = prefs.edit()
        if (packageName == null) e.remove(KEY_SELECTED_APP) else e.putString(KEY_SELECTED_APP, packageName)
        e.apply()
    }

    fun setSelectedOutput(out: OutputSelection?) {
        _selectedOutput.value = out
        val e = prefs.edit()
        if (out == null) {
            e.remove(KEY_OUTPUT_TYPE).remove(KEY_OUTPUT_ID).remove(KEY_OUTPUT_NAME)
        } else {
            e.putString(KEY_OUTPUT_TYPE, out.type)
            e.putString(KEY_OUTPUT_ID, out.id)
            e.putString(KEY_OUTPUT_NAME, out.name)
        }
        e.apply()
    }

    // ── (De)serialization helpers ──────────────────────────────────────
    private fun loadApps(): List<AppEntry> {
        val raw = prefs.getString(KEY_APPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AppEntry(
                    packageName = o.optString("pkg"),
                    displayName = o.optString("label"),
                    sampleRate = o.optInt("rate", 44100),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveApps(list: List<AppEntry>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("pkg", a.packageName)
                    .put("label", a.displayName)
                    .put("rate", a.sampleRate),
            )
        }
        prefs.edit().putString(KEY_APPS, arr.toString()).apply()
    }

    private fun loadOutput(): OutputSelection? {
        val type = prefs.getString(KEY_OUTPUT_TYPE, null) ?: return null
        val id = prefs.getString(KEY_OUTPUT_ID, null) ?: return null
        return OutputSelection(type, id, prefs.getString(KEY_OUTPUT_NAME, null) ?: "")
    }

    private companion object {
        const val NAME = "app_settings"
        const val KEY_APPS = "apps_json"
        const val KEY_SELECTED_APP = "selected_app"
        const val KEY_OUTPUT_TYPE = "output_type"
        const val KEY_OUTPUT_ID = "output_id"
        const val KEY_OUTPUT_NAME = "output_name"
        const val KEY_DIGITAL_VOLUME = "digital_volume"
        const val KEY_VOLUME = "volume_"
        const val KEY_BATTERY_ASKED = "battery_asked"
    }
}
