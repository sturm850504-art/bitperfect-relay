package com.bitperfect.relay.data

/**
 * Stage 9 settings screen: one OSS library entry.
 *
 * [swappable] marks the libraries the app *designates* as local-load targets
 * per `docs/ref/libs.md` (libusb — plain C, dlopen-able). The engine
 * dlopens it (dl/dllib.cpp) and can swap them at runtime from
 * `files/libs/<name>/<version>/<abi>/` — applied live, with auto-restore of
 * the previous version on load failure.
 *
 * ABI note: a local `.so` must expose the same stable C ABI (same function
 * signatures and opaque-handle usage) — it is called through function
 * pointers, not linked.
 *
 * Everything else (Kotlin / Java / toolchain) is display-only.
 * soxr and Oboe were removed with the Bluetooth output (2026-10-11).
 *
 * Versions are the ones actually in use (gradle/libs.versions.toml for the
 * managed deps; vendored native versions for the C/C++ libs). Licenses:
 * libusb = LGPL-2.1, the rest follow the
 * Apache-2.0 family shipped by their upstreams.
 */
data class RelayLibrary(
    val key: String,          // stable id, e.g. "libusb"
    val name: String,         // display name, e.g. "libusb"
    val version: String,      // version currently bundled/used
    val license: String,      // SPDX-style identifier
    val kind: LibraryKind,    // how it is used
    val swappable: Boolean,   // true only for designated local-load targets (engine dlopen-swaps them at runtime)
)

/** How the app uses a library — drives the settings-screen section. */
enum class LibraryKind { NATIVE, ANDROIDX, TOOLCHAIN }

/**
 * The full, static catalog of OSS the app depends on. Not backed by prefs —
 * it is a fixed description of what ships in this APK.
 */
object LibraryCatalog {

    val all: List<RelayLibrary> = listOf(
        // ── native (engine) ──
        RelayLibrary("libusb", "libusb", "1.0.27", "LGPL-2.1", LibraryKind.NATIVE, swappable = true),
        // ── androidx / kotlin ──
        RelayLibrary("compose-bom", "Compose BOM", "2026.02.01", "Apache-2.0", LibraryKind.ANDROIDX, swappable = false),
        RelayLibrary("core-ktx", "AndroidX core-ktx", "1.17.0", "Apache-2.0", LibraryKind.ANDROIDX, swappable = false),
        RelayLibrary("activity-compose", "AndroidX activity-compose", "1.10.1", "Apache-2.0", LibraryKind.ANDROIDX, swappable = false),
        RelayLibrary("datastore", "AndroidX datastore-preferences", "1.2.1", "Apache-2.0", LibraryKind.ANDROIDX, swappable = false),
        RelayLibrary("lifecycle", "AndroidX lifecycle-runtime-ktx", "2.10.0", "Apache-2.0", LibraryKind.ANDROIDX, swappable = false),
        RelayLibrary("coroutines", "kotlinx-coroutines-android", "1.11.0", "Apache-2.0", LibraryKind.ANDROIDX, swappable = false),
        RelayLibrary("okhttp", "OkHttp", "5.5.0", "Apache-2.0", LibraryKind.ANDROIDX, swappable = false),
        // ── toolchain ──
        RelayLibrary("kotlin", "Kotlin", "2.2.10", "Apache-2.0", LibraryKind.TOOLCHAIN, swappable = false),
        RelayLibrary("agp", "Android Gradle Plugin", "9.4.1", "Apache-2.0", LibraryKind.TOOLCHAIN, swappable = false),
    )

    fun byKey(key: String): RelayLibrary? = all.firstOrNull { it.key == key }

    val swappable: List<RelayLibrary> = all.filter { it.swappable }
}
