// How many iso OUT transfers to keep in flight and how many packets each carries.
// Pure math so it can be unit-tested off the USB path (app/src/test/cpp).
//
// Why: the old fixed plan (8 transfers of about 2 ms)
// kept only ~16 ms queued in the host controller; UAPP keeps its whole buffer (default 200 ms)
// queued. 100 ms default (2026-10-24): on the S25 FE + BTR17 it streams clean and the user heard
// a steady tone where the old 16 ms queue wavered. Known limit: with a Bluetooth headset active on
// the phone, 50 ms or more stalls when the music app resumes (docs/ref/usb-dac.md); that setup is
// outside the defined flow and the stall watchdog reports it. Override for A/B tests.
// Limits: at most 16 KiB per transfer (UAPP's default cap), at most 128 packets per transfer
// (one usbfs URB; libusb splits larger iso transfers into several URBs, which we avoid), at least
// kMinXfers transfers so a completion never leaves the queue nearly empty, at most kMaxXfers.
#ifndef BITPERFECT_XFER_PLAN_H
#define BITPERFECT_XFER_PLAN_H

#include <cstdint>

struct XferPlan {
    int packets_per_xfer = 0;
    int xfers = 0;
    int in_flight_us = 0;  // audio time queued when all transfers are in flight
};

constexpr int kXferMaxBytes = 16384;
constexpr int kXferMaxPackets = 128;
constexpr int kMinXfers = 8;
constexpr int kMaxXfers = 64;
constexpr int kDefaultInFlightMs = 100;

// pps: packets per second of the data endpoint. max_packet_bytes: per packet, extra
// transactions included. in_flight_ms: target queued audio time.
// max_ppt: optional cap on packets per transfer (diagnostics).
inline XferPlan plan_transfers(uint32_t pps, int max_packet_bytes, int in_flight_ms,
                               int max_ppt = kXferMaxPackets) {
    XferPlan p;
    if (pps == 0 || max_packet_bytes <= 0) return p;
    if (in_flight_ms < 1) in_flight_ms = 1;
    long total = static_cast<long>(pps) * in_flight_ms / 1000;
    if (total < 1) total = 1;

    int ppt = kXferMaxBytes / max_packet_bytes;
    if (ppt > kXferMaxPackets) ppt = kXferMaxPackets;
    if (max_ppt >= 1 && ppt > max_ppt) ppt = max_ppt;
    long spread = (total + kMinXfers - 1) / kMinXfers;  // keep at least kMinXfers transfers
    if (ppt > spread) ppt = static_cast<int>(spread);
    if (ppt < 1) ppt = 1;

    long n = (total + ppt - 1) / ppt;
    if (n < 2) n = 2;
    if (n > kMaxXfers) n = kMaxXfers;

    p.packets_per_xfer = ppt;
    p.xfers = static_cast<int>(n);
    p.in_flight_us = static_cast<int>(static_cast<long long>(n) * ppt * 1000000LL / pps);
    return p;
}

#endif  // BITPERFECT_XFER_PLAN_H
