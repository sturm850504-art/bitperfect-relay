// Runtime .so swap implementation (Stage 9). See dllib.h for the contract.

#include "dl/dllib.h"

#include <android/log.h>
#include <cstdarg>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <sys/stat.h>

// Global tables (C++ linkage, matching the extern declarations in dllib.h).
UsbLibTable  g_usb;

namespace {

constexpr const char* kTag = "BitPerfectLibs";
void LogI(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, kTag, fmt, ap);
    va_end(ap);
}
void LogE(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, kTag, fmt, ap);
    va_end(ap);
}

std::mutex  g_lock;
std::string g_lastError;

// Capture the dlopen/dlsym failure message (and clear any stale one).
void CaptureDLError() {
    const char* msg = dlerror();
    g_lastError = msg ? msg : "unknown dlopen/dlsym failure";
}

bool FileExists(const char* path) {
    struct stat st;
    return path != nullptr && path[0] != '\0' && ::stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

template <typename T>
bool Resolve(void* handle, const char* name, T*& out) {
    void* sym = dlsym(handle, name);
    if (sym == nullptr) { CaptureDLError(); return false; }
    out = reinterpret_cast<T*>(sym);
    return true;
}

bool ResolveUsb(void* handle) {
    UsbLibTable& t = g_usb;
    // clang-format off
    if (!Resolve(handle, "libusb_init",                           t.p_init))                           return false;
    if (!Resolve(handle, "libusb_exit",                           t.p_exit))                           return false;
    if (!Resolve(handle, "libusb_set_debug",                      t.p_set_debug))                      return false;
    if (!Resolve(handle, "libusb_set_option",                     t.p_set_option))                     return false;
    if (!Resolve(handle, "libusb_wrap_sys_device",                t.p_wrap_sys_device))                return false;
    if (!Resolve(handle, "libusb_strerror",                       t.p_strerror))                        return false;
    if (!Resolve(handle, "libusb_get_device",                     t.p_get_device))                     return false;
    if (!Resolve(handle, "libusb_close",                          t.p_close))                          return false;
    if (!Resolve(handle, "libusb_get_active_config_descriptor",   t.p_get_active_config_descriptor))   return false;
    if (!Resolve(handle, "libusb_get_config_descriptor",          t.p_get_config_descriptor))          return false;
    if (!Resolve(handle, "libusb_free_config_descriptor",         t.p_free_config_descriptor))         return false;
    if (!Resolve(handle, "libusb_detach_kernel_driver",           t.p_detach_kernel_driver))           return false;
    if (!Resolve(handle, "libusb_claim_interface",                t.p_claim_interface))                return false;
    if (!Resolve(handle, "libusb_release_interface",              t.p_release_interface))              return false;
    if (!Resolve(handle, "libusb_set_interface_alt_setting",      t.p_set_interface_alt_setting))      return false;
    if (!Resolve(handle, "libusb_control_transfer",               t.p_control_transfer))               return false;
    if (!Resolve(handle, "libusb_alloc_transfer",                 t.p_alloc_transfer))                 return false;
    if (!Resolve(handle, "libusb_free_transfer",                  t.p_free_transfer))                  return false;
    if (!Resolve(handle, "libusb_submit_transfer",                t.p_submit_transfer))                return false;
    if (!Resolve(handle, "libusb_cancel_transfer",                t.p_cancel_transfer))                return false;
    if (!Resolve(handle, "libusb_handle_events_timeout",          t.p_handle_events_timeout))          return false;
    if (!Resolve(handle, "libusb_handle_events_timeout_completed", t.p_handle_events_timeout_completed)) return false;
    if (!Resolve(handle, "libusb_interrupt_event_handler",        t.p_interrupt_event_handler))        return false;
    if (!Resolve(handle, "libusb_get_device_speed",               t.p_get_device_speed))               return false;
    // clang-format on
    return true;
}


// dlopen [path], bind all pointers into the libusb table.
// On failure the table is left empty and g_lastError holds the reason.
bool LoadUsb(const char* path) {
    g_usb = UsbLibTable{};
    if (!FileExists(path)) { g_lastError = std::string("libusb: file not found: ") + (path ? path : ""); return false; }
    dlerror();  // clear any stale message before we dlopen
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) { CaptureDLError(); return false; }
    g_usb.handle = handle;
    g_usb.loadedPath = path ? path : "";
    if (!ResolveUsb(handle)) {
        dlclose(handle);
        g_usb = UsbLibTable{};
        g_lastError = "libusb: " + g_lastError;
        return false;
    }
    return true;
}


}  // namespace

extern "C" {

int dllib_init(const char* nativeLibDir) {
    std::lock_guard<std::mutex> lock(g_lock);
    // Already bound: the activity can be recreated while the relay streams, and reloading
    // would clear the table under the libusb event thread (null call, crash 2026-10-25).
    if (g_usb.handle != nullptr) return 0;
    std::string base = nativeLibDir ? nativeLibDir : "";
    std::string usbPath = base.empty() ? "libusb.so" : base + "/libusb.so";

    LogI("dllib_init: loading %s", usbPath.c_str());
    bool usbOk = LoadUsb(usbPath.c_str());
    if (usbOk) LogI("libusb bound (24 symbols)");
    else LogE("dllib_init failed: %s", g_lastError.c_str());
    return usbOk ? 0 : -1;
}

int dllib_apply(const char* key, const char* newPath) {
    if (key == nullptr || newPath == nullptr) { g_lastError = "null key or path"; return -1; }
    std::string oldPath;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (std::string(key) != "libusb") { g_lastError = "unknown key: " + std::string(key); return -1; }
        oldPath = g_usb.loadedPath;
        // Already loaded from exactly this path → no-op success.
        if (oldPath == newPath) return 0;
    }

    // 1) Unload the current one (refcount drop; table reset).
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (g_usb.handle) dlclose(g_usb.handle);
        g_usb = UsbLibTable{};
    }
    LogI("dllib_apply(%s): unloaded %s, trying %s", key,
         oldPath.empty() ? "(none)" : oldPath.c_str(), newPath);

    // 2) Load the new one.
    bool ok = LoadUsb(newPath);
    if (ok) {
        LogI("dllib_apply(%s): loaded %s", key, newPath);
        return 0;
    }

    // 3) Auto-restore the previous version (libs.md contract).
    std::string reason = g_lastError;
    LogE("dllib_apply(%s): failed (%s); restoring %s", key, reason.c_str(), oldPath.c_str());
    if (oldPath.empty()) {
        g_lastError = reason + " [no previous version to restore]";
        return -1;
    }
    bool restored = LoadUsb(oldPath.c_str());
    g_lastError = restored
        ? reason + " — previous version restored"
        : reason + " — restore of " + oldPath + " also FAILED";
    return -1;
}

const char* dllib_loaded_path(const char* key) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (key == nullptr) return nullptr;
    if (std::string(key) == "libusb") return g_usb.handle ? g_usb.loadedPath.c_str() : nullptr;
    return nullptr;
}

const char* dllib_last_error(void) {
    std::lock_guard<std::mutex> lock(g_lock);
    return g_lastError.c_str();
}

}  // extern "C"
