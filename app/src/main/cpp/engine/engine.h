#ifndef BITPERFECT_ENGINE_H
#define BITPERFECT_ENGINE_H

#ifdef __cplusplus
extern "C" {
#endif

/* ── Lifecycle ──────────────────────────────────────────── */

/** Initialize the engine. Returns 0 on success, negative on error. */
int engine_init(void);

/** Tear down the engine and free all resources. */
void engine_destroy(void);

/* ── Configuration ──────────────────────────────────────── */

/**
 * Set the minimum log level for engine output.
 * 0 = verbose, 1 = debug, 2 = info, 3 = warning, 4 = error
 */
void engine_set_log_level(int level);

/* ── Query ──────────────────────────────────────────────── */

/** Return the engine version string (e.g. "0.1.0"). */
const char* engine_get_version(void);

/* ── Runtime library swap (Stage 9) ──────────────────────── */

/**
 * Swap the loaded libusb.so to [path] at runtime.
 * Quiesces the engine's outputs first (so no live handle/transfer is owned
 * by the .so about to be unloaded), then dlopen-swap (dl/dllib.cpp) with
 * auto-restore of the previous version on failure.
 * [key] is "libusb". Returns 0 on success, -1 on failure
 * (see engine_lib_last_error).
 */
int engine_apply_lib(const char* key, const char* path);

/** Path of the .so currently loaded for [key], or NULL if not loaded.
 *  Borrowed pointer; valid until the next engine/dllib call. */
const char* engine_lib_active_path(const char* key);

/** Last library-swap error, or "" if none. Borrowed pointer. */
const char* engine_lib_last_error(void);

/* ── Logging ────────────────────────────────────────────── */

/**
 * Emit a log message from native code.
 * @param level  0=verbose … 4=error
 * @param tag    Log tag (fall back to "BitPerfect" if NULL)
 * @param msg    Message body (must not be NULL)
 */
void engine_native_log(int level, const char* tag, const char* msg);

#ifdef __cplusplus
}
#endif

#endif /* BITPERFECT_ENGINE_H */
