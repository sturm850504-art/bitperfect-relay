#include "engine.h"

#include <android/log.h>
#include <mutex>
#include <string>

#include "dl/dllib.h"
#include "usb/usbplay.h"

#define BP_TAG "BitPerfect"
#define BP_LOGI(...) __android_log_print(ANDROID_LOG_INFO,    BP_TAG, __VA_ARGS__)
#define BP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   BP_TAG, __VA_ARGS__)
#define BP_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   BP_TAG, __VA_ARGS__)

namespace {
constexpr char kVersion[] = "0.1.0";
int g_logLevel = 1;  // debug
bool g_initialized = false;
std::mutex g_mutex;
}  // namespace

extern "C" {

int engine_init(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return 0;
    g_initialized = true;
    BP_LOGI("engine_init: BitPerfect engine %s ready", kVersion);
    return 0;
}

void engine_destroy(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return;
    g_initialized = false;
    BP_LOGI("engine_destroy: engine released");
}

void engine_set_log_level(int level) {
    if (level < 0) level = 0;
    if (level > 4) level = 4;
    g_logLevel = level;
}

const char* engine_get_version(void) {
    return kVersion;
}

void engine_native_log(int level, const char* tag, const char* msg) {
    int lvl;
    switch (level) {
        case 0:  lvl = ANDROID_LOG_VERBOSE; break;
        case 1:  lvl = ANDROID_LOG_DEBUG;   break;
        case 2:  lvl = ANDROID_LOG_INFO;    break;
        case 3:  lvl = ANDROID_LOG_WARN;    break;
        default: lvl = ANDROID_LOG_ERROR;   break;
    }
    if (lvl < g_logLevel) return;
    __android_log_print(lvl, tag ? tag : BP_TAG, "%s", msg ? msg : "");
}

// ── Runtime library swap (Stage 9) ──────────────────────────
// The engine calls libusb through the g_usb table (dl/dllib.h),
// so swapping the .so under us requires no re-linking — but it MUST happen
// while no engine thread is inside the library: a submitted libusb transfer
// would dangle the moment dlclose unloads the .so.
// Quiescing = stop the USB pump (idempotent).

int engine_apply_lib(const char* key, const char* path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (key == nullptr || path == nullptr) {
        engine_native_log(4, BP_TAG, "engine_apply_lib: null key or path");
        return -1;
    }
    usbplay_close();        // cancels transfers, joins the pump, closes libusb and its context

    BP_LOGI("engine_apply_lib(%s): quiesced, swapping to %s", key, path);
    int r = dllib_apply(key, path);
    if (r != 0) BP_LOGE("engine_apply_lib(%s) failed: %s", key, dllib_last_error());
    return r;
}

const char* engine_lib_active_path(const char* key) {
    return dllib_loaded_path(key);
}

const char* engine_lib_last_error(void) {
    return dllib_last_error();
}

}  // extern "C"

// ── JNI bindings ───────────────────────────────────────────
// Kotlin: com.bitperfect.relay.Engine { nativeInit, nativeDestroy, nativeVersion, nativeSetLogLevel }
#include <jni.h>

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_relay_Engine_nativeInit(JNIEnv* env, jobject, jstring dir) {
    engine_init();
    const char* c = dir ? env->GetStringUTFChars(dir, nullptr) : nullptr;
    dllib_init(c ? c : "");
    if (c) env->ReleaseStringUTFChars(dir, c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_relay_Engine_nativeDestroy(JNIEnv*, jobject) {
    engine_destroy();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitperfect_relay_Engine_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(engine_get_version());
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_relay_Engine_nativeSetLogLevel(JNIEnv*, jobject, jint level) {
    engine_set_log_level(static_cast<int>(level));
}

// ── USB output (usbplay byte pump; Kotlin side: output/usb/UsbDacOutput.kt) ──

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbOpen(JNIEnv*, jclass, jint fd) {
    return usbplay_open(static_cast<int>(fd));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbSpeed(JNIEnv*, jclass) {
    return usbplay_speed();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbStart(JNIEnv*, jclass, jint epOut, jint maxPacketBytes,
                                               jint bInterval, jint epFb, jint fbPacketBytes,
                                               jint rateHz, jint frameBytes, jboolean fillMax,
                                               jint primeMs, jint capacityMs) {
    return usbplay_start(epOut, maxPacketBytes, bInterval, epFb, fbPacketBytes, rateHz,
                         frameBytes, fillMax ? 1 : 0, primeMs, capacityMs);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbWrite(JNIEnv* env, jclass, jbyteArray data, jint len) {
    if (len < 0 || len > env->GetArrayLength(data)) return -1;
    // Critical section is short: usbplay_write only copies into the ring and never blocks.
    void* p = env->GetPrimitiveArrayCritical(data, nullptr);
    if (p == nullptr) return -1;
    int r = usbplay_write(static_cast<const uint8_t*>(p), static_cast<int>(len));
    env->ReleasePrimitiveArrayCritical(data, p, JNI_ABORT);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbOk(JNIEnv*, jclass) {
    return usbplay_ok();
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbStats(JNIEnv* env, jclass, jlongArray out) {
    long long v[USBPLAY_STATS] = {};
    usbplay_stats(v, USBPLAY_STATS);
    jlong j[USBPLAY_STATS];
    for (int i = 0; i < USBPLAY_STATS; i++) j[i] = static_cast<jlong>(v[i]);
    jsize n = env->GetArrayLength(out);
    env->SetLongArrayRegion(out, 0, n < USBPLAY_STATS ? n : USBPLAY_STATS, j);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbStop(JNIEnv*, jclass) {
    usbplay_stop();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbReattach(JNIEnv*, jclass, jint fd, jint iface) {
    return usbplay_reattach(fd, iface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbClose(JNIEnv*, jclass) {
    usbplay_close();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(usbplay_last_error());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitperfect_relay_Engine_nativeUsbLog(JNIEnv* env, jclass) {
    static char buf[32 * 320];
    usbplay_log(buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

// ── Runtime library swap (Stage 9, dl/dllib.cpp) ────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_relay_Engine_nativeApplyLib(JNIEnv* env, jclass, jstring key, jstring path) {
    if (key == nullptr || path == nullptr) return -1;
    const char* k = env->GetStringUTFChars(key, nullptr);
    if (k == nullptr) return -1;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) { env->ReleaseStringUTFChars(key, k); return -1; }
    int r = engine_apply_lib(k, p);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(path, p);
    return r;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitperfect_relay_Engine_nativeLibActivePath(JNIEnv* env, jclass, jstring key) {
    if (key == nullptr) return nullptr;
    const char* k = env->GetStringUTFChars(key, nullptr);
    if (k == nullptr) return nullptr;
    const char* p = engine_lib_active_path(k);
    jstring result = p ? env->NewStringUTF(p) : nullptr;
    env->ReleaseStringUTFChars(key, k);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitperfect_relay_Engine_nativeLibLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(engine_lib_last_error());
}
