// Packet sizing and feedback parsing for a USB Audio iso OUT endpoint.
// Follows Linux snd-usb-audio (sound/usb/endpoint.c); facts in docs/ref/uac.md sections 4-5.
// Pure logic, no USB calls, so it can be unit-tested off-device (app/src/test/cpp).
// Not thread-safe: call init/next_frames/on_feedback from one thread (the libusb event thread).
#ifndef BITPERFECT_ISO_CLOCK_H
#define BITPERFECT_ISO_CLOCK_H

#include <atomic>
#include <climits>
#include <cstdint>

struct IsoClock {
    bool high_speed = false;
    int interval = 0;          // log2 of packet interval in (micro)frames
    uint32_t rate = 0;
    int frame_bytes = 0;
    int max_frames = 0;        // whole frames that fit in one packet
    bool fill_max = false;     // UAC1 MaxPacketsOnly: every packet is max size
    bool async = false;        // feedback endpoint present

    uint32_t pps = 0;          // packets per second
    uint32_t packsize[2] = {0, 0};
    uint32_t sample_rem = 0;
    uint32_t sample_accum = 0;

    uint32_t freqn = 0;        // nominal frames per (micro)frame, Q16.16
    uint32_t freqmax = 0;
    std::atomic<uint32_t> freqm{0};  // current rate from feedback; read by stats from other threads
    uint32_t phase = 0;
    int freqshift = INT_MIN;   // INT_MIN = feedback format not detected yet
    std::atomic<uint32_t> fb_raw{0};  // last raw feedback value and length, for diagnostics
    std::atomic<int> fb_len{0};
    std::atomic<uint32_t> fb_rejected{0};

    // b_interval: data endpoint bInterval. max_packet_bytes already includes extra transactions.
    // Returns 0, or -1 if the nominal packet does not fit in max_packet_bytes.
    int init(bool hs, int b_interval, uint32_t rate_hz, int frame_bytes_, int max_packet_bytes,
             bool fill_max_, bool async_) {
        high_speed = hs;
        interval = (hs && b_interval >= 1 && b_interval <= 4) ? b_interval - 1 : 0;
        rate = rate_hz;
        frame_bytes = frame_bytes_;
        fill_max = fill_max_;
        async = async_;
        if (frame_bytes <= 0 || rate == 0) return -1;
        max_frames = max_packet_bytes / frame_bytes;

        pps = (hs ? 8000u : 1000u) >> interval;
        sample_rem = rate % pps;
        sample_accum = 0;
        packsize[0] = rate / pps;
        packsize[1] = (rate + pps - 1) / pps;
        if (packsize[1] > static_cast<uint32_t>(max_frames)) return -1;

        // 64-bit: rate << 13 overflows 32 bits above 524 kHz.
        uint64_t r = rate;
        freqn = static_cast<uint32_t>(hs ? ((r << 10) + 62) / 125 : ((r << 13) + 62) / 125);
        freqmax = freqn + (freqn >> 1);
        uint64_t maxsz = (((static_cast<uint64_t>(freqmax) << interval) + 0xffff) >> 16) * frame_bytes;
        if (maxsz > static_cast<uint64_t>(max_packet_bytes))
            freqmax = static_cast<uint32_t>(max_frames) << (16 - interval);
        freqm.store(freqn, std::memory_order_relaxed);
        phase = 0;
        freqshift = INT_MIN;
        return 0;
    }

    // Frames to put in the next packet.
    int next_frames() {
        if (fill_max) return max_frames;
        if (async) {
            phase = (phase & 0xffff) + (freqm.load(std::memory_order_relaxed) << interval);
            uint32_t n = phase >> 16;
            return n < static_cast<uint32_t>(max_frames) ? static_cast<int>(n) : max_frames;
        }
        sample_accum += sample_rem;
        if (sample_accum >= pps) {
            sample_accum -= pps;
            return static_cast<int>(packsize[1]);
        }
        return static_cast<int>(packsize[0]);
    }

    // One completed feedback packet. Returns true if the value was accepted.
    bool on_feedback(const uint8_t* p, int len) {
        if (len < 3) return false;
        uint32_t f = p[0] | (p[1] << 8) | (static_cast<uint32_t>(p[2]) << 16);
        if (len >= 4) f |= static_cast<uint32_t>(p[3]) << 24;
        fb_raw.store(f, std::memory_order_relaxed);
        fb_len.store(len, std::memory_order_relaxed);
        f &= (len == 3) ? 0x00ffffffu : 0x0fffffffu;
        if (f == 0) return false;

        if (freqshift == INT_MIN) {
            // First value: shift until it is near nominal (assumes -25% .. +50%).
            int shift = 0;
            while (f < freqn - freqn / 4) { f <<= 1; shift++; }
            while (f > freqn + freqn / 2) { f >>= 1; shift--; }
            freqshift = shift;
        } else if (freqshift >= 0) {
            f <<= freqshift;
        } else {
            f >>= -freqshift;
        }

        // [AI judgment] Stricter than Linux (-12.5% .. +50%): accept only within 0.5% of nominal.
        // Real clock offsets are ~0.01%; iDSD Valkyrie reports a steady +1.3% and overflows if
        // followed, while the exact nominal rate plays clean (device test 2026-10-11).
        uint32_t tol = freqn / 200;
        if (f >= freqn - tol && f <= freqn + tol && f <= freqmax) {
            freqm.store(f, std::memory_order_relaxed);
            return true;
        }
        fb_rejected.fetch_add(1, std::memory_order_relaxed);
        freqshift = INT_MIN;  // maybe the format guess was wrong; detect again next time
        return false;
    }
};

#endif  // BITPERFECT_ISO_CLOCK_H
