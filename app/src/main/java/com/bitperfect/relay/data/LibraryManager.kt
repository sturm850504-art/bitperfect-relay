package com.bitperfect.relay.data

import android.content.Context
import com.bitperfect.relay.Engine
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A locally-provided native `.so` candidate for a swappable library. */
data class LocalLibFile(
    val path: String,      // absolute path
    val version: String,   // directory name under files/libs/<key>/
    val abi: String,       // directory name under <version>/
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,    // lowercase hex; integrity fingerprint
)

/**
 * Stage 9: local library-swap management.
 *
 * Discovers user-placed `.so` files under
 * `files/libs/<key>/<version>/<abi>/` for the running ABI, computes a SHA-256
 * integrity fingerprint for each, persists the per-library selected version,
 * and applies the selection at runtime: `Engine.applyLib` dlopen-swaps the
 * `.so` (bundled default or local) with auto-restore on failure
 * (docs/ref/libs.md contract). A restart re-applies the persisted selection
 * from the same source.
 *
 * Honesty note: GitHub download is inactive (no repo), so there is no external
 * trusted hash to match against. "Verify" here means the file is present,
 * non-empty and readable (its SHA-256 computes). This is a local integrity
 * fingerprint, not a signature against a publisher's attested hash.
 */
class LibraryManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _selected = MutableStateFlow(loadSelected())
    val selected: StateFlow<Map<String, String>> = _selected

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** ABI this process runs on (the only ABI a local `.so` can be loaded as). */
    val runningAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private val libsRoot: File = File(appContext.filesDir, "libs")

    /**
     * Enumerate local swap candidates for [key] under the running ABI.
     * Runs file I/O off the main thread.
     */
    suspend fun discover(key: String): List<LocalLibFile> = withContext(Dispatchers.IO) {
        val root = File(libsRoot, key)
        if (!root.isDirectory) return@withContext emptyList()
        buildList {
            root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { verDir ->
                val abiDir = File(verDir, runningAbi)
                if (!abiDir.isDirectory) return@forEach
                abiDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }
                    ?.sortedBy { it.name }
                    ?.forEach { f ->
                        val size = f.length()
                        if (size <= 0) return@forEach // skip empty/corrupt
                        val sha = sha256(f) ?: return@forEach
                        add(LocalLibFile(f.absolutePath, verDir.name, abiDir.name, f.name, size, sha))
                    }
            }
        }
    }

    /** Compute a file's SHA-256 (lowercase hex). Null if it can't be read. */
    private fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    fun selectedVersion(key: String): String? = _selected.value[key]

    /** Bundled `.so` path for [key] (jniLibs), or null for non-swappable keys. */
    fun bundledPath(key: String): String? = when (key) {
        "libusb" -> "${appContext.applicationInfo.nativeLibraryDir}/libusb.so"
        else -> null
    }

    /** Local `.so` path for [key]/[version] under the running ABI, or null if not present. */
    fun localFilePath(key: String, version: String): String? {
        val dir = File(libsRoot, "$key/$version/$runningAbi")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.minByOrNull { it.name }
            ?.absolutePath
    }

    /**
     * Persist the selection for [key] AND apply it at runtime (Engine.applyLib
     * — dlopen swap with auto-restore on failure). [version] null = bundled
     * default. Returns 0 on success, -1 otherwise (Engine.libLastError has
     * details).
     */
    fun selectAndApply(key: String, version: String?): Int {
        setSelected(key, version)
        return applySelection(key, version)
    }

    /**
     * Apply the selection for [key] at runtime (Engine.applyLib — dlopen swap
     * with auto-restore on failure). [version] null = bundled default.
     * Returns 0 on success, -1 otherwise (Engine.libLastError has details).
     */
    fun applySelection(key: String, version: String?): Int {
        val path = if (version == null) {
            bundledPath(key)
        } else {
            localFilePath(key, version)
        }
        if (path == null || !File(path).isFile) return -1
        return Engine.applyLib(key, path)
    }

    /** Persist the selected version for [key] (null clears it). */
    fun setSelected(key: String, version: String?) {
        val next = _selected.value.toMutableMap()
        if (version == null) next.remove(key) else next[key] = version
        _selected.value = next
        val e = prefs.edit().clear()
        next.forEach { (k, v) -> e.putString("sel_$k", v) }
        e.apply()
    }

    fun setTransientMessage(msg: String?) {
        _message.value = msg
    }

    private fun loadSelected(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("sel_") && v is String) out[k.removePrefix("sel_")] = v
        }
        return out
    }

    private companion object {
        const val NAME = "library_settings"
    }
}
