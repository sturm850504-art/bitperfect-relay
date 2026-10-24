#ifndef BITPERFECT_DLLIB_H
#define BITPERFECT_DLLIB_H

/*
 * Runtime .so swap support (Stage 9). C++-only header (all consumers are .cpp).
 *
 * libusb is built as a shared library (libusb.so,
 * bundled in the APK's jniLibs). The engine does NOT link them — instead it
 * calls them through the function-pointer tables below, bound at load time
 * via dlopen/dlsym. This lets a user-selected local .so be swapped in at
 * runtime and restored on failure (docs/ref/libs.md contract:
 * verify -> stop relay -> unload -> load new -> auto-restore on failure).
 *
 * Callers (usbplay.cpp) map their symbol calls onto the table
 * entries with #define shims, so call sites keep their normal syntax.
 *
 * ABI note: a local .so must be API-compatible (same stable C ABI: same
 * function signatures and opaque-handle usage). It is NOT linked against
 * the engine, only called through these pointers.
 *
 * Thread safety: all entry points take an internal mutex. A swap may only
 * happen while no engine thread is inside the library (the engine quiesces
 * its outputs before calling dllib_apply — see engine.cpp).
 */

#include <cstddef>
#include <string>
#include <sys/time.h>

#include <libusb.h>  /* opaque types + exact signatures for the usb table */

/* ── Function tables ──────────────────────────────────────── */

struct UsbLibTable {
    void* handle = nullptr; /* dlopen handle, null until loaded */
    std::string loadedPath;

    /* core — signatures copied from vendored libusb 1.0.27 libusb.h.
     * Using the real parameter types (not void*) keeps the call sites
     * type-correct C++ and makes the dlsym cast a like-type conversion. */
    int  (*p_init)(libusb_context** ctx) = nullptr;
    void (*p_exit)(libusb_context* ctx) = nullptr;
    void (*p_set_debug)(libusb_context* ctx, int level) = nullptr;
    int (*p_set_option)(libusb_context* ctx, enum libusb_option option, ...) = nullptr;
    int (*p_wrap_sys_device)(libusb_context* ctx, intptr_t sysDev,
                             libusb_device_handle** outDev) = nullptr;
    const char* (*p_strerror)(int errcode) = nullptr;
    libusb_device* (*p_get_device)(libusb_device_handle* devHandle) = nullptr;

    /* device lifecycle / descriptors */
    void (*p_close)(libusb_device_handle* dev) = nullptr;
    int (*p_get_active_config_descriptor)(libusb_device* dev,
                                          libusb_config_descriptor** cfg) = nullptr;
    int (*p_get_config_descriptor)(libusb_device* dev, unsigned char configIndex,
                                   libusb_config_descriptor** cfg) = nullptr;
    void (*p_free_config_descriptor)(libusb_config_descriptor* cfg) = nullptr;

    /* interface / control */
    int (*p_detach_kernel_driver)(libusb_device_handle* dev, int interfaceNumber) = nullptr;
    int (*p_claim_interface)(libusb_device_handle* dev, int interfaceNumber) = nullptr;
    int (*p_release_interface)(libusb_device_handle* dev, int interfaceNumber) = nullptr;
    int (*p_set_interface_alt_setting)(libusb_device_handle* dev, int interfaceNumber,
                                       int alternateSetting) = nullptr;
    int (*p_control_transfer)(libusb_device_handle* dev, unsigned char requestType,
                              unsigned char request, unsigned short valueIndex,
                              unsigned short index, unsigned char* data,
                              unsigned short wLength, unsigned int timeout) = nullptr;

    /* async transfers */
    libusb_transfer* (*p_alloc_transfer)(int isoPackets) = nullptr;
    void  (*p_free_transfer)(libusb_transfer* transfer) = nullptr;
    int   (*p_submit_transfer)(libusb_transfer* transfer) = nullptr;
    int   (*p_cancel_transfer)(libusb_transfer* transfer) = nullptr;
    int   (*p_handle_events_timeout)(libusb_context* ctx, struct timeval* tv) = nullptr;
    int   (*p_handle_events_timeout_completed)(libusb_context* ctx, struct timeval* tv,
                                               int* completed) = nullptr;
    void  (*p_interrupt_event_handler)(libusb_context* ctx) = nullptr;
    int   (*p_get_device_speed)(libusb_device* dev) = nullptr;
};


/* Global function tables (C++ linkage — contain std::string). */
extern UsbLibTable  g_usb;

/* ── Loader API (C linkage; engine.cpp calls these as C) ───── */

extern "C" {

/*
 * Load the bundled defaults: <nativeLibDir>/libusb.so.
 * Call once at engine init. If either dlopen fails the corresponding table
 * stays empty and the engine's entry points report "not loaded" (honest
 * error, no crash). Returns 0 if both loaded, -1 if either failed (see
 * dllib_last_error).
 */
int dllib_init(const char* nativeLibDir);

/*
 * Swap the library identified by [key] ("libusb") to [newPath].
 * Unloads the currently loaded .so, loads [newPath], rebinds all pointers.
 * If loading [newPath] fails, the previously loaded .so is reloaded
 * (auto-restore). Returns 0 on success, -1 on failure.
 */
int dllib_apply(const char* key, const char* newPath);

/* Path currently loaded for [key], or nullptr if not loaded.
 * Borrowed pointer; valid until the next dllib_* call. */
const char* dllib_loaded_path(const char* key);

/* Last loader error (dlopen/dlsym message), or "" if none. Borrowed. */
const char* dllib_last_error(void);

}  /* extern "C" */

#endif /* BITPERFECT_DLLIB_H */
