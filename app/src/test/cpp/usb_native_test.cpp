// Unit tests for the header-only USB pump parts (iso_clock.h, byte_ring.h, xfer_plan.h).
// Built with the NDK and run on the emulator or a device: tools/native-test/run.sh
#include <cstdio>
#include <thread>
#include <vector>

#include "byte_ring.h"
#include "iso_clock.h"
#include "xfer_plan.h"

static int g_fail = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); g_fail++; } } while (0)

static long sum_frames(IsoClock& c, int packets) {
    long s = 0;
    for (int i = 0; i < packets; i++) s += c.next_frames();
    return s;
}

static void test_sync_sizing() {
    IsoClock c;
    // High speed, bInterval 1 -> every microframe, 48 kHz -> 6 frames each.
    CHECK(c.init(true, 1, 48000, 8, 1024, false, false) == 0);
    CHECK(c.interval == 0 && c.pps == 8000);
    CHECK(sum_frames(c, 8000) == 48000);
    // 44.1 kHz high speed: 5 or 6 frames, exact over one second.
    CHECK(c.init(true, 1, 44100, 8, 1024, false, false) == 0);
    CHECK(c.packsize[0] == 5 && c.packsize[1] == 6);
    CHECK(sum_frames(c, 8000) == 44100);
    // Full speed: 1 ms frames; bInterval ignored.
    CHECK(c.init(false, 4, 44100, 4, 192, false, false) == 0);
    CHECK(c.interval == 0 && c.pps == 1000);
    CHECK(sum_frames(c, 1000) == 44100);
    // High speed bInterval 4 -> one packet per 8 microframes.
    CHECK(c.init(true, 4, 96000, 8, 1024, false, false) == 0);
    CHECK(c.interval == 3 && c.pps == 1000);
    CHECK(sum_frames(c, 1000) == 96000);
    // bInterval out of 1..4 at high speed -> interval 0.
    CHECK(c.init(true, 7, 48000, 8, 1024, false, false) == 0);
    CHECK(c.interval == 0);
}

static void test_limits() {
    IsoClock c;
    // 384 kHz 2ch x 4 bytes needs 48 frames = 384 bytes per microframe.
    CHECK(c.init(true, 1, 384000, 8, 200, false, false) == -1);
    CHECK(c.init(true, 1, 384000, 8, 384, false, false) == 0);
    // MaxPacketsOnly: always max frames.
    CHECK(c.init(false, 1, 44100, 4, 196, true, false) == 0);
    CHECK(c.next_frames() == 49 && c.next_frames() == 49);
    CHECK(c.init(true, 1, 48000, 0, 1024, false, false) == -1);
}

static void test_async_nominal() {
    IsoClock c;
    CHECK(c.init(true, 1, 48000, 8, 1024, false, true) == 0);
    CHECK(c.freqn == (6u << 16));
    CHECK(sum_frames(c, 8000) == 48000);
    CHECK(c.init(true, 1, 44100, 8, 1024, false, true) == 0);
    long s = sum_frames(c, 8000);
    CHECK(s >= 44099 && s <= 44101);
    CHECK(c.init(false, 1, 48000, 8, 1024, false, true) == 0);
    CHECK(c.freqn == (48u << 16));
}

static void test_feedback() {
    IsoClock c;
    CHECK(c.init(true, 1, 48000, 8, 1024, false, true) == 0);
    // High speed 16.16, 4 bytes: 6.0 frames per microframe.
    uint8_t hs[4] = {0x00, 0x00, 0x06, 0x00};
    CHECK(c.on_feedback(hs, 4));
    CHECK(c.freqshift == 0 && c.freqm.load() == (6u << 16));
    // A steady +1.3% report (iDSD Valkyrie) is rejected; nominal stays.
    uint32_t v = static_cast<uint32_t>(6.0811 * 65536);
    uint8_t valk[4] = {uint8_t(v), uint8_t(v >> 8), uint8_t(v >> 16), uint8_t(v >> 24)};
    CHECK(!c.on_feedback(valk, 4));
    CHECK(c.freqm.load() == (6u << 16));
    CHECK(c.fb_rejected.load() == 1);
    // Slightly fast DAC: 6.01 -> about 48080 frames per second.
    uint32_t f = static_cast<uint32_t>(6.01 * 65536);
    uint8_t fast[4] = {uint8_t(f), uint8_t(f >> 8), uint8_t(f >> 16), uint8_t(f >> 24)};
    CHECK(c.on_feedback(fast, 4));
    long s = sum_frames(c, 8000);
    CHECK(s >= 48079 && s <= 48081);
    // Way out of range: rejected and the format is detected again.
    uint8_t bad[4] = {0x00, 0x00, 0x0c, 0x00};
    c.freqm.store(c.freqn);
    CHECK(!c.on_feedback(bad, 4));
    CHECK(c.freqshift == INT_MIN);

    // High speed device sending 10.14 in 3 bytes (common deviation): 6.0 = 98304.
    CHECK(c.init(true, 1, 48000, 8, 1024, false, true) == 0);
    uint8_t ten14[3] = {0x00, 0x80, 0x01};
    CHECK(c.on_feedback(ten14, 3));
    CHECK(c.freqshift == 2 && c.freqm.load() == (6u << 16));
    // Remembered shift applies to the next value.
    CHECK(c.on_feedback(ten14, 3) && c.freqm.load() == (6u << 16));

    // Full speed 10.14: 48.0 frames per frame = 786432.
    CHECK(c.init(false, 1, 48000, 8, 1024, false, true) == 0);
    uint8_t fs[3] = {0x00, 0x00, 0x0c};
    CHECK(c.on_feedback(fs, 3));
    CHECK(c.freqm.load() == (48u << 16));

    // Short or zero packets are ignored.
    CHECK(c.init(true, 1, 48000, 8, 1024, false, true) == 0);
    CHECK(!c.on_feedback(hs, 2));
    uint8_t zero[4] = {0, 0, 0, 0};
    CHECK(!c.on_feedback(zero, 4));
    CHECK(c.freqm.load() == c.freqn);

    // freqmax clamps to what fits in max packet: exactly 6 frames -> 6.1 is rejected.
    CHECK(c.init(true, 1, 48000, 8, 48, false, true) == 0);
    CHECK(c.freqmax == (6u << 16));
    uint32_t g = static_cast<uint32_t>(6.1 * 65536);
    uint8_t over[4] = {uint8_t(g), uint8_t(g >> 8), uint8_t(g >> 16), uint8_t(g >> 24)};
    CHECK(!c.on_feedback(over, 4));
    CHECK(c.next_frames() == 6);
}

static void test_ring_basic() {
    ByteRing r;
    r.reset(10);
    uint8_t a[7] = {1, 2, 3, 4, 5, 6, 7}, b[10] = {};
    CHECK(r.write(a, 7) && r.size() == 7);
    CHECK(!r.write(a, 4));            // all or nothing
    CHECK(r.size() == 7);
    CHECK(r.read(b, 5) && b[0] == 1 && b[4] == 5);
    CHECK(!r.read(b, 3));             // only 2 left
    CHECK(r.write(a, 7));             // wraps around the end
    CHECK(r.read(b, 9));
    CHECK(b[0] == 6 && b[1] == 7 && b[2] == 1 && b[8] == 7);
    CHECK(r.size() == 0);
}

static void test_ring_threads() {
    ByteRing r;
    r.reset(4096);
    const int total = 4 * 1000 * 1000;
    bool ok = true;
    std::thread prod([&] {
        uint8_t chunk[333];
        int n = 0;
        while (n < total) {
            int len = total - n < 333 ? total - n : 333;
            for (int i = 0; i < len; i++) chunk[i] = static_cast<uint8_t>((n + i) * 7);
            if (r.write(chunk, len)) n += len;
            else std::this_thread::yield();
        }
    });
    uint8_t buf[100];
    int n = 0;
    while (n < total) {
        int len = total - n < 100 ? total - n : 100;
        if (!r.read(buf, len)) { std::this_thread::yield(); continue; }
        for (int i = 0; i < len; i++) if (buf[i] != static_cast<uint8_t>((n + i) * 7)) ok = false;
        n += len;
    }
    prod.join();
    CHECK(ok);
}

static void test_xfer_plan() {
    // FiiO BTR17 at the default (100 ms): 10 transfers of 81 packets = 101.25 ms.
    XferPlan d = plan_transfers(8000, 200, kDefaultInFlightMs);
    CHECK(d.packets_per_xfer == 81 && d.xfers == 10 && d.in_flight_us == 101250);
    // 32 ms (works with a phone BT headset active): 8 transfers of 32 packets.
    d = plan_transfers(8000, 200, 32);
    CHECK(d.packets_per_xfer == 32 && d.xfers == 8 && d.in_flight_us == 32000);
    // Diagnostic cap on packets per transfer: 100 ms as 25 x 32.
    d = plan_transfers(8000, 200, 100, 32);
    CHECK(d.packets_per_xfer == 32 && d.xfers == 25 && d.in_flight_us == 100000);
    // FiiO BTR17, 100 ms (the task's first target):
    // 16 KiB / 200 B = 81 packets per transfer, 10 transfers = 810 packets = 101.25 ms.
    XferPlan p = plan_transfers(8000, 200, 100);
    CHECK(p.packets_per_xfer == 81 && p.xfers == 10);
    CHECK(p.in_flight_us == 101250);
    CHECK(p.packets_per_xfer * 200 <= kXferMaxBytes);
    // 1024-byte packets: 16 per transfer (16 KiB cap), 50 transfers, exactly 100 ms.
    p = plan_transfers(8000, 1024, 100);
    CHECK(p.packets_per_xfer == 16 && p.xfers == 50 && p.in_flight_us == 100000);
    // Old behaviour reachable for A/B: 16 ms at high speed = 8 transfers of 16 packets.
    p = plan_transfers(8000, 200, 16);
    CHECK(p.packets_per_xfer == 16 && p.xfers == 8 && p.in_flight_us == 16000);
    // Full speed (1 ms packets): spread over at least kMinXfers transfers.
    p = plan_transfers(1000, 196, 100);
    CHECK(p.xfers >= kMinXfers && p.packets_per_xfer == 13);
    CHECK(p.in_flight_us >= 100000 && p.in_flight_us <= 110000);
    // Small packets hit the 128-packet URB cap and the transfer cap.
    p = plan_transfers(8000, 16, 500);
    CHECK(p.packets_per_xfer <= kXferMaxPackets && p.xfers <= kMaxXfers);
    // Very short target still keeps two transfers.
    p = plan_transfers(1000, 196, 1);
    CHECK(p.xfers == 2 && p.packets_per_xfer == 1);
    // Invalid input: empty plan.
    p = plan_transfers(0, 200, 100);
    CHECK(p.xfers == 0);
    p = plan_transfers(8000, 0, 100);
    CHECK(p.xfers == 0);
}

int main() {
    test_xfer_plan();
    test_sync_sizing();
    test_limits();
    test_async_nominal();
    test_feedback();
    test_ring_basic();
    test_ring_threads();
    std::printf(g_fail ? "FAILED %d\n" : "ALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
