#include "usbplay.h"
#include <libusb.h>
#include "dl/dllib.h"
#define libusb_init                            (g_usb.p_init)
#define libusb_exit                            (g_usb.p_exit)
#define libusb_set_option                      (g_usb.p_set_option)
#define libusb_wrap_sys_device                 (g_usb.p_wrap_sys_device)
#define libusb_strerror                        (g_usb.p_strerror)
#define libusb_get_device                      (g_usb.p_get_device)
#define libusb_get_device_speed                (g_usb.p_get_device_speed)
#define libusb_close                           (g_usb.p_close)
#define libusb_alloc_transfer                  (g_usb.p_alloc_transfer)
#define libusb_free_transfer                   (g_usb.p_free_transfer)
#define libusb_submit_transfer                 (g_usb.p_submit_transfer)
#define libusb_cancel_transfer                 (g_usb.p_cancel_transfer)
#define libusb_handle_events_timeout_completed (g_usb.p_handle_events_timeout_completed)
#define libusb_interrupt_event_handler         (g_usb.p_interrupt_event_handler)

#include "iso_clock.h"
#include "byte_ring.h"
#include "xfer_plan.h"

#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <new>
#include <string>
#include <sys/resource.h>
#include <sys/system_properties.h>
#include <sys/syscall.h>
#include <thread>
#include <vector>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {

constexpr int kFbXfers = 2;

libusb_context* ctx = nullptr;
libusb_device_handle* dev = nullptr;

IsoClock iso_clock;
ByteRing ring;

int frame_bytes_ = 0;
int prime_bytes_ = 0;
XferPlan plan_;

std::vector<libusb_transfer*> data_xfers;
std::vector<uint8_t*> data_bufs;
libusb_transfer* fb_xfers[kFbXfers] = {};
uint8_t* fb_bufs[kFbXfers] = {};

std::thread event_thread;
std::atomic<bool> running{false};
std::atomic<bool> failed{false};
std::atomic<int> in_flight{0};
std::atomic<bool> priming{false};

std::atomic<long long> stat_underruns{0};
std::atomic<long long> stat_silent_frames{0};
std::atomic<long long> stat_dropped{0};
std::atomic<long long> stat_err_packets{0};
std::atomic<long long> stat_sent_frames{0};    // all frames sent, real or silent
std::atomic<long long> stat_short_packets{0};  // OUT packets that sent fewer bytes than queued
std::atomic<long long> stat_completions{0};    // completed data transfers
std::atomic<long long> last_completion_ns{0};
std::atomic<long long> stat_restarts{0};       // stall recoveries (cancel and resubmit)
// Set by the stall watchdog: cancelled data transfers are refilled and resubmitted at once.
std::atomic<bool> restarting{false};
int failed_restarts = 0;                       // event thread only
constexpr int kStallMs = 300;
constexpr int kMaxFailedRestarts = 3;

long long now_ns() {
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

char err_buf[256] = {};
std::mutex err_mutex;

// Last native log lines for the in-app diagnostics (the app does not read system logcat).
// Each entry is "<epoch ms>|<level>|<text>". Written rarely (start, stop, errors).
constexpr int kLogLines = 32;
std::string log_lines[kLogLines];
int log_next = 0;
int log_count = 0;
std::mutex log_mutex;

void ulog(int prio, const char* fmt, ...) {
    char tmp[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    __android_log_print(prio, "BitPerfect", "%s", tmp);

    struct timespec ts {};
    clock_gettime(CLOCK_REALTIME, &ts);
    long long ms = static_cast<long long>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
    char level = prio >= ANDROID_LOG_ERROR ? 'E' : prio == ANDROID_LOG_WARN ? 'W' : 'I';
    char line[300];
    snprintf(line, sizeof(line), "%lld|%c|%s", ms, level, tmp);
    std::lock_guard<std::mutex> lk(log_mutex);
    log_lines[log_next] = line;
    log_next = (log_next + 1) % kLogLines;
    if (log_count < kLogLines) log_count++;
}

void set_error(const char* fmt, ...) {
    char tmp[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);

    {
        std::lock_guard<std::mutex> lk(err_mutex);
        std::strncpy(err_buf, tmp, sizeof(err_buf) - 1);
        err_buf[sizeof(err_buf) - 1] = '\0';
    }
    ulog(ANDROID_LOG_ERROR, "%s", tmp);
}

void clear_error() {
    std::lock_guard<std::mutex> lk(err_mutex);
    err_buf[0] = '\0';
}

// Integer system property, or def when unset or not a number (diagnostic knobs only).
int int_prop(const char* name, int def) {
    char prop[PROP_VALUE_MAX] = {};
    if (__system_property_get(name, prop) <= 0) return def;
    char* end = nullptr;
    long v = std::strtol(prop, &end, 10);
    return (end != prop) ? static_cast<int>(v) : def;
}

// Fill a data transfer's iso packets from the ring. Runs on the event thread
// or in usbplay_start (before the thread exists).
void fill_data_transfer(libusb_transfer* xfer, uint8_t* buf) {
    unsigned total = 0;
    int npkts = xfer->num_iso_packets;
    for (int i = 0; i < npkts; i++) {
        int frames = iso_clock.next_frames();
        int bytes = frames * frame_bytes_;

        if (priming.load(std::memory_order_relaxed) &&
            static_cast<long long>(ring.size()) >= prime_bytes_) {
            priming.store(false, std::memory_order_relaxed);
        }

        uint8_t* dst = buf + total;
        if (!priming.load(std::memory_order_relaxed) && ring.read(dst, bytes)) {
            // real data copied
        } else {
            if (!priming.load(std::memory_order_relaxed)) {
                stat_underruns.fetch_add(1, std::memory_order_relaxed);
                priming.store(true, std::memory_order_relaxed);
            }
            std::memset(dst, 0, bytes);
            stat_silent_frames.fetch_add(frames, std::memory_order_relaxed);
        }

        stat_sent_frames.fetch_add(frames, std::memory_order_relaxed);
        xfer->iso_packet_desc[i].length = static_cast<unsigned>(bytes);
        total += static_cast<unsigned>(bytes);
    }
    xfer->length = total;
}

void data_callback(libusb_transfer* xfer) {
    bool resubmitted = false;

    switch (xfer->status) {
        case LIBUSB_TRANSFER_COMPLETED: {
            if (restarting.exchange(false, std::memory_order_relaxed))
                ulog(ANDROID_LOG_WARN, "USB stall recovered (restart %lld)",
                     stat_restarts.load(std::memory_order_relaxed));
            stat_completions.fetch_add(1, std::memory_order_relaxed);
            last_completion_ns.store(now_ns(), std::memory_order_relaxed);
            for (int i = 0; i < xfer->num_iso_packets; i++) {
                if (xfer->iso_packet_desc[i].status != LIBUSB_TRANSFER_COMPLETED)
                    stat_err_packets.fetch_add(1, std::memory_order_relaxed);
                else if (xfer->iso_packet_desc[i].actual_length < xfer->iso_packet_desc[i].length)
                    stat_short_packets.fetch_add(1, std::memory_order_relaxed);
            }
            if (running.load(std::memory_order_relaxed)) {
                int idx = static_cast<int>(reinterpret_cast<uintptr_t>(xfer->user_data));
                fill_data_transfer(xfer, data_bufs[idx]);
                if (libusb_submit_transfer(xfer) == 0) {
                    resubmitted = true;
                } else {
                    set_error("resubmit data xfer %d failed", idx);
                    failed.store(true, std::memory_order_relaxed);
                    running.store(false, std::memory_order_relaxed);
                }
            }
            break;
        }
        case LIBUSB_TRANSFER_CANCELLED:
            // Stall recovery: queue the transfer again with fresh data (ASAP scheduling).
            if (running.load(std::memory_order_relaxed) && restarting.load(std::memory_order_relaxed)) {
                int idx = static_cast<int>(reinterpret_cast<uintptr_t>(xfer->user_data));
                fill_data_transfer(xfer, data_bufs[idx]);
                if (libusb_submit_transfer(xfer) == 0) {
                    resubmitted = true;
                } else {
                    set_error("resubmit data xfer %d after stall failed", idx);
                    failed.store(true, std::memory_order_relaxed);
                    running.store(false, std::memory_order_relaxed);
                }
            }
            break;
        case LIBUSB_TRANSFER_NO_DEVICE:
            set_error("DAC disconnected");
            failed.store(true, std::memory_order_relaxed);
            running.store(false, std::memory_order_relaxed);
            break;
        default:
            set_error("data xfer status %d", xfer->status);
            failed.store(true, std::memory_order_relaxed);
            running.store(false, std::memory_order_relaxed);
            break;
    }

    if (!resubmitted)
        in_flight.fetch_sub(1, std::memory_order_relaxed);
}

void fb_callback(libusb_transfer* xfer) {
    bool resubmitted = false;

    switch (xfer->status) {
        case LIBUSB_TRANSFER_COMPLETED: {
            if (xfer->num_iso_packets > 0 &&
                xfer->iso_packet_desc[0].status == LIBUSB_TRANSFER_COMPLETED) {
                iso_clock.on_feedback(static_cast<const uint8_t*>(xfer->buffer),
                                 xfer->iso_packet_desc[0].actual_length);
            }
            if (running.load(std::memory_order_relaxed)) {
                if (libusb_submit_transfer(xfer) == 0) {
                    resubmitted = true;
                } else {
                    set_error("resubmit fb xfer failed");
                    failed.store(true, std::memory_order_relaxed);
                    running.store(false, std::memory_order_relaxed);
                }
            }
            break;
        }
        case LIBUSB_TRANSFER_CANCELLED:
            break;
        case LIBUSB_TRANSFER_NO_DEVICE:
            set_error("DAC disconnected");
            failed.store(true, std::memory_order_relaxed);
            running.store(false, std::memory_order_relaxed);
            break;
        default:
            set_error("fb xfer status %d", xfer->status);
            failed.store(true, std::memory_order_relaxed);
            running.store(false, std::memory_order_relaxed);
            break;
    }

    if (!resubmitted)
        in_flight.fetch_sub(1, std::memory_order_relaxed);
}

void cancel_all() {
    for (auto* x : data_xfers)
        if (x) libusb_cancel_transfer(x);
    for (int i = 0; i < kFbXfers; i++)
        if (fb_xfers[i]) libusb_cancel_transfer(fb_xfers[i]);
}

void event_thread_main() {
    if (setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), -16) != 0) {
        ulog(ANDROID_LOG_WARN, "setpriority failed: %s", strerror(errno));
    }

    struct timeval tv = {0, 100000};  // 100 ms
    failed_restarts = 0;
    last_completion_ns.store(now_ns(), std::memory_order_relaxed);
    while (running.load(std::memory_order_relaxed) ||
           in_flight.load(std::memory_order_relaxed) > 0) {
        int rc = libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        // Stall watchdog. On Samsung (S25 FE) the system's own USB audio path touches the DAC
        // while we stream (docs/ref/usb-dac.md, 2026-10-24); with a deep queue the host then
        // stops completing our transfers for good. Recovery: cancel the data transfers and queue
        // them again; give up with an error after kMaxFailedRestarts tries without progress.
        long long idle_ms = (now_ns() - last_completion_ns.load(std::memory_order_relaxed)) / 1000000;
        if (running.load(std::memory_order_relaxed) && idle_ms > kStallMs) {
            if (restarting.load(std::memory_order_relaxed)) failed_restarts++;
            else failed_restarts = 0;
            if (failed_restarts >= kMaxFailedRestarts) {
                set_error("DAC stopped taking audio (USB stall)");
                failed.store(true, std::memory_order_relaxed);
                running.store(false, std::memory_order_relaxed);
                cancel_all();
            } else {
                long long n = stat_restarts.fetch_add(1, std::memory_order_relaxed) + 1;
                ulog(ANDROID_LOG_WARN, "USB stall: no data transfer completed for %lld ms "
                     "(completions %lld, in flight %d); restart %lld", idle_ms,
                     stat_completions.load(std::memory_order_relaxed),
                     in_flight.load(std::memory_order_relaxed), n);
                restarting.store(true, std::memory_order_relaxed);
                last_completion_ns.store(now_ns(), std::memory_order_relaxed);
                for (auto* x : data_xfers)
                    if (x) libusb_cancel_transfer(x);
            }
        }
        if (rc < 0) {
            // ASSUMPTION: LIBUSB_ERROR_INTERRUPTED is the normal stop path (called
            // by libusb_interrupt_event_handler in usbplay_stop); skip it.
            if (rc != LIBUSB_ERROR_INTERRUPTED) {
                set_error("event loop: %s", libusb_strerror(rc));
                failed.store(true, std::memory_order_relaxed);
                running.store(false, std::memory_order_relaxed);
                cancel_all();
            }
        }
    }
}

void free_all_transfers() {
    for (auto*& x : data_xfers) {
        if (x) libusb_free_transfer(x);
        x = nullptr;
    }
    for (auto*& b : data_bufs) {
        delete[] b;
        b = nullptr;
    }
    data_xfers.clear();
    data_bufs.clear();
    for (int i = 0; i < kFbXfers; i++) {
        if (fb_xfers[i]) {
            libusb_free_transfer(fb_xfers[i]);
            fb_xfers[i] = nullptr;
        }
        if (fb_bufs[i]) {
            delete[] fb_bufs[i];
            fb_bufs[i] = nullptr;
        }
    }
}

}  // namespace

extern "C" {

int usbplay_open(int fd) {
    if (dev != nullptr) {
        set_error("already open");
        return -1;
    }

    if (g_usb.p_init == nullptr) {
        set_error("libusb not loaded");
        return -1;
    }

    if (ctx == nullptr) {
        int orc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
        if (orc != 0)
            ulog(ANDROID_LOG_WARN, "NO_DEVICE_DISCOVERY: %s", libusb_strerror(orc));
        if (libusb_init(&ctx) < 0) {
            set_error("libusb_init failed");
            ctx = nullptr;
            return -1;
        }
    }

    if (libusb_wrap_sys_device(ctx, static_cast<intptr_t>(fd), &dev) < 0) {
        set_error("libusb_wrap_sys_device failed");
        dev = nullptr;
        return -1;
    }

    clear_error();
    return 0;
}

int usbplay_speed(void) {
    if (dev == nullptr) return -1;
    return libusb_get_device_speed(libusb_get_device(dev));
}

int usbplay_start(int ep_out, int max_packet_bytes, int b_interval,
                  int ep_fb, int fb_packet_bytes,
                  int rate_hz, int frame_bytes, int fill_max,
                  int prime_ms, int capacity_ms) {
    if (dev == nullptr) {
        set_error("not open");
        return -1;
    }
    if (running.load(std::memory_order_relaxed)) {
        set_error("already streaming");
        return -1;
    }

    int speed = usbplay_speed();
    bool high_speed = (speed >= LIBUSB_SPEED_HIGH);

    // Diagnostic knob (adb shell setprop debug.bitperfect.nofb 1): size packets at the nominal
    // rate and only log the feedback. Used to test whether a DAC's feedback is trustworthy.
    bool ignore_fb = int_prop("debug.bitperfect.nofb", 0) == 1;
    if (ignore_fb) ulog(ANDROID_LOG_WARN, "feedback ignored for sizing (debug.bitperfect.nofb=1)");

    if (iso_clock.init(high_speed, b_interval, static_cast<uint32_t>(rate_hz),
                   frame_bytes, max_packet_bytes, fill_max != 0, ep_fb != 0 && !ignore_fb) != 0) {
        set_error("max packet too small for rate");
        return -1;
    }

    // Diagnostic knob for A/B tests (adb shell setprop debug.bitperfect.inflight_ms N).
    int want_ms = int_prop("debug.bitperfect.inflight_ms", kDefaultInFlightMs);
    if (want_ms < 2 || want_ms > 500) want_ms = kDefaultInFlightMs;
    // Diagnostic knob: cap packets per transfer (adb shell setprop debug.bitperfect.max_ppt N).
    int max_ppt = int_prop("debug.bitperfect.max_ppt", kXferMaxPackets);
    plan_ = plan_transfers(iso_clock.pps, max_packet_bytes, want_ms, max_ppt);
    if (plan_.xfers <= 0) {
        set_error("no transfer plan");
        return -1;
    }

    // The ring must hold the prime level plus what one round of completions takes out of it.
    int in_flight_ms = (plan_.in_flight_us + 999) / 1000;
    if (capacity_ms < prime_ms + 2 * in_flight_ms) capacity_ms = prime_ms + 2 * in_flight_ms;

    frame_bytes_ = frame_bytes;
    prime_bytes_ = rate_hz * prime_ms / 1000 * frame_bytes;

    size_t ring_cap = static_cast<size_t>(rate_hz) * capacity_ms / 1000 * frame_bytes;
    ring.reset(ring_cap);

    ulog(ANDROID_LOG_INFO, "USB queue: %d xfers x %d packets = %.1f ms in flight (target %d ms), "
         "prime %d ms, ring %d ms, pps %u, maxPacket %d B",
         plan_.xfers, plan_.packets_per_xfer, plan_.in_flight_us / 1000.0, want_ms,
         prime_ms, capacity_ms, iso_clock.pps, max_packet_bytes);

    stat_underruns.store(0, std::memory_order_relaxed);
    stat_silent_frames.store(0, std::memory_order_relaxed);
    stat_dropped.store(0, std::memory_order_relaxed);
    stat_err_packets.store(0, std::memory_order_relaxed);
    stat_sent_frames.store(0, std::memory_order_relaxed);
    stat_short_packets.store(0, std::memory_order_relaxed);
    stat_completions.store(0, std::memory_order_relaxed);
    stat_restarts.store(0, std::memory_order_relaxed);
    restarting.store(false, std::memory_order_relaxed);
    priming.store(true, std::memory_order_relaxed);
    failed.store(false, std::memory_order_relaxed);
    in_flight.store(0, std::memory_order_relaxed);
    clear_error();

    int ppt = plan_.packets_per_xfer;
    int buf_size = ppt * max_packet_bytes;
    data_xfers.assign(plan_.xfers, nullptr);
    data_bufs.assign(plan_.xfers, nullptr);

    for (int i = 0; i < plan_.xfers; i++) {
        data_bufs[i] = new (std::nothrow) uint8_t[buf_size];
        if (data_bufs[i] == nullptr) {
            set_error("out of memory allocating data buffer %d", i);
            goto fail_start;
        }
        data_xfers[i] = libusb_alloc_transfer(ppt);
        if (data_xfers[i] == nullptr) {
            set_error("libusb_alloc_transfer failed for data xfer %d", i);
            goto fail_start;
        }
        // fill_iso_transfer sets num_iso_packets and overwrites length, so it goes first.
        libusb_fill_iso_transfer(data_xfers[i], dev, static_cast<unsigned char>(ep_out),
                                 data_bufs[i], buf_size, ppt, data_callback,
                                 reinterpret_cast<void*>(static_cast<uintptr_t>(i)), 0);
        fill_data_transfer(data_xfers[i], data_bufs[i]);

        int rc = libusb_submit_transfer(data_xfers[i]);
        if (rc != 0) {
            set_error("submit data xfer %d: %s", i, libusb_strerror(rc));
            goto fail_start;
        }
        in_flight.fetch_add(1, std::memory_order_relaxed);
    }

    if (ep_fb != 0) {
        for (int i = 0; i < kFbXfers; i++) {
            fb_bufs[i] = new (std::nothrow) uint8_t[fb_packet_bytes];
            if (fb_bufs[i] == nullptr) {
                set_error("out of memory allocating fb buffer %d", i);
                goto fail_start;
            }
            fb_xfers[i] = libusb_alloc_transfer(1);
            if (fb_xfers[i] == nullptr) {
                set_error("libusb_alloc_transfer failed for fb xfer %d", i);
                goto fail_start;
            }
            libusb_fill_iso_transfer(fb_xfers[i], dev, static_cast<unsigned char>(ep_fb),
                                     fb_bufs[i], fb_packet_bytes, 1, fb_callback, nullptr, 0);
            libusb_set_iso_packet_lengths(fb_xfers[i], static_cast<unsigned>(fb_packet_bytes));

            int rc = libusb_submit_transfer(fb_xfers[i]);
            if (rc != 0) {
                set_error("submit fb xfer %d: %s", i, libusb_strerror(rc));
                goto fail_start;
            }
            in_flight.fetch_add(1, std::memory_order_relaxed);
        }
    }

    running.store(true, std::memory_order_relaxed);
    event_thread = std::thread(event_thread_main);
    return 0;

fail_start:
    running.store(false, std::memory_order_relaxed);
    cancel_all();
    {
        struct timeval tv = {0, 100000};
        while (in_flight.load(std::memory_order_relaxed) > 0)
            libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
    }

    free_all_transfers();
    in_flight.store(0, std::memory_order_relaxed);
    return -1;
}

int usbplay_write(const uint8_t* data, int len) {
    if (!running.load(std::memory_order_relaxed) || failed.load(std::memory_order_relaxed))
        return -1;
    if (frame_bytes_ <= 0 || len <= 0 || len % frame_bytes_ != 0)
        return -1;

    if (ring.write(data, static_cast<size_t>(len)))
        return len;

    stat_dropped.fetch_add(len / frame_bytes_, std::memory_order_relaxed);
    return 0;
}

int usbplay_ok(void) {
    return failed.load(std::memory_order_relaxed) ? -1 : 0;
}

void usbplay_stats(long long* out, int n) {
    int count = (n < USBPLAY_STATS) ? n : USBPLAY_STATS;
    if (count < 0) count = 0;
    for (int i = 0; i < count; i++) {
        switch (i) {
            case 0: out[i] = stat_underruns.load(std::memory_order_relaxed); break;
            case 1: out[i] = stat_silent_frames.load(std::memory_order_relaxed); break;
            case 2: out[i] = stat_dropped.load(std::memory_order_relaxed); break;
            case 3: out[i] = static_cast<long long>(iso_clock.freqm.load(std::memory_order_relaxed)); break;
            case 4: out[i] = stat_err_packets.load(std::memory_order_relaxed); break;
            case 5: out[i] = frame_bytes_ > 0 ? static_cast<long long>(ring.size()) / frame_bytes_ : 0; break;
            case 6: out[i] = stat_sent_frames.load(std::memory_order_relaxed); break;
            case 7: out[i] = iso_clock.fb_raw.load(std::memory_order_relaxed); break;
            case 8: out[i] = iso_clock.fb_len.load(std::memory_order_relaxed); break;
            case 9: out[i] = iso_clock.freqshift; break;
            case 10: out[i] = stat_short_packets.load(std::memory_order_relaxed); break;
            case 11: out[i] = iso_clock.fb_rejected.load(std::memory_order_relaxed); break;
            case 12: out[i] = plan_.in_flight_us; break;
            case 13: out[i] = plan_.xfers; break;
            case 14: out[i] = plan_.packets_per_xfer; break;
            case 15: out[i] = stat_restarts.load(std::memory_order_relaxed); break;
        }
    }
}

void usbplay_stop(void) {
    if (!event_thread.joinable()) return;

    running.store(false, std::memory_order_relaxed);

    for (size_t i = 0; i < data_xfers.size(); i++) {
        if (data_xfers[i]) {
            int rc = libusb_cancel_transfer(data_xfers[i]);
            if (rc != 0 && rc != LIBUSB_ERROR_NOT_FOUND)
                ulog(ANDROID_LOG_WARN, "cancel data xfer %zu: %s", i, libusb_strerror(rc));
        }
    }
    for (int i = 0; i < kFbXfers; i++) {
        if (fb_xfers[i]) {
            int rc = libusb_cancel_transfer(fb_xfers[i]);
            if (rc != 0 && rc != LIBUSB_ERROR_NOT_FOUND)
                ulog(ANDROID_LOG_WARN, "cancel fb xfer %d: %s", i, libusb_strerror(rc));
        }
    }

    if (ctx != nullptr)
        libusb_interrupt_event_handler(ctx);

    event_thread.join();

    free_all_transfers();
    in_flight.store(0, std::memory_order_relaxed);
}

int usbplay_reattach(int fd, int iface) {
    // USBDEVFS_CONNECT: the kernel binds a driver only if none holds the interface (docs/ref/uac.md 3).
    struct usbdevfs_ioctl cmd = {iface, USBDEVFS_CONNECT, nullptr};
    int rc = ioctl(fd, USBDEVFS_IOCTL, &cmd);
    if (rc < 0) {
        int e = errno;
        ulog(ANDROID_LOG_WARN, "reattach iface %d: %s", iface, strerror(e));
        return -e;
    }
    ulog(ANDROID_LOG_INFO, "reattach iface %d: %d", iface, rc);
    return rc;
}

void usbplay_close(void) {
    usbplay_stop();
    if (dev != nullptr) {
        libusb_close(dev);
        dev = nullptr;
    }
    if (ctx != nullptr) {
        libusb_exit(ctx);
        ctx = nullptr;
    }
}

const char* usbplay_last_error(void) {
    std::lock_guard<std::mutex> lk(err_mutex);
    return err_buf;
}

int usbplay_log(char* out, int cap) {
    if (out == nullptr || cap <= 0) return 0;
    std::string all;
    {
        std::lock_guard<std::mutex> lk(log_mutex);
        int start = (log_next - log_count + kLogLines) % kLogLines;
        for (int i = 0; i < log_count; i++) {
            all += log_lines[(start + i) % kLogLines];
            all += '\n';
        }
    }
    int n = static_cast<int>(all.size());
    if (n > cap - 1) n = cap - 1;
    std::memcpy(out, all.data(), n);
    out[n] = '\0';
    return n;
}

}  // extern "C"
