// USB DAC output, native side: a byte pump for one isochronous OUT endpoint.
//
// Split (rewrite 2026-10-11, docs/ref/usb-dac.md): Kotlin (output/usb/) parses descriptors,
// claims interfaces, selects the alternate setting, sets the sample rate and packs PCM.
// This side only moves the packed bytes to the DAC with libusb async iso transfers,
// sizes packets from the clock (iso_clock.h) and reads the feedback endpoint.
// C ABI changed from the Qwen-era usbplay (stage 4/5): that engine was wrong at the root
// (docs/eval/phase-a-audit.md defects 1-15).
#ifndef BITPERFECT_USBPLAY_H
#define BITPERFECT_USBPLAY_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Wrap the UsbDeviceConnection fd with libusb. The fd stays owned by Kotlin.
 *  0 on success, negative on failure (see usbplay_last_error). */
int usbplay_open(int fd);

/** libusb_speed of the open device (1 low, 2 full, 3 high, 4 super, 5 super plus, 0 unknown),
 *  or -1 if no device is open. */
int usbplay_speed(void);

/** Start streaming. The interfaces must already be claimed, the alternate setting selected
 *  and the rate set (Kotlin does that).
 *  ep_out: data endpoint address. max_packet_bytes: wMaxPacketSize bits 10..0 times
 *  (1 + bits 12..11). b_interval: data endpoint bInterval.
 *  ep_fb: feedback IN endpoint address, 0 if none. fb_packet_bytes: its max packet bytes.
 *  frame_bytes: channels * subslot bytes. fill_max: UAC1 MaxPacketsOnly flag.
 *  prime_ms: buffered audio needed before real data is sent (at start and after an underrun).
 *  capacity_ms: ring size; writes that do not fit are dropped. Raised if smaller than
 *  prime_ms + 2 x the in-flight time.
 *  Queue depth: 100 ms of audio in flight by default (xfer_plan.h explains the limit);
 *  override for A/B tests with `adb shell setprop debug.bitperfect.inflight_ms N` (2..500) and
 *  `debug.bitperfect.max_ppt N` (packets per transfer cap). A stall watchdog cancels and
 *  re-queues the data transfers when the host stops completing them (gives up after 3 tries).
 *  0 on success, negative on failure. */
int usbplay_start(int ep_out, int max_packet_bytes, int b_interval,
                  int ep_fb, int fb_packet_bytes,
                  int rate_hz, int frame_bytes, int fill_max,
                  int prime_ms, int capacity_ms);

/** Queue packed PCM bytes (whole frames). Never blocks.
 *  Returns bytes queued (0 if the ring is full; the chunk is dropped and counted),
 *  or negative if not streaming or the stream failed. */
int usbplay_write(const uint8_t* data, int len);

/** 0 = streaming or cleanly stopped, -1 = the stream failed (see usbplay_last_error). */
int usbplay_ok(void);

/** Number of values usbplay_stats can fill. */
#define USBPLAY_STATS 16

/** Counters since start. out[0] underruns, out[1] silent frames sent, out[2] dropped frames,
 *  out[3] current feedback rate (Q16.16 frames per (micro)frame, nominal if no feedback),
 *  out[4] iso packets that completed with an error, out[5] frames buffered in the ring,
 *  out[6] frames sent to the DAC (real and silent), out[7] last raw feedback value,
 *  out[8] its length in bytes, out[9] detected feedback shift, out[10] short OUT packets,
 *  out[11] feedback values rejected as implausible, out[12] audio queued in the host controller
 *  when all data transfers are in flight (microseconds), out[13] data transfers,
 *  out[14] packets per data transfer, out[15] stall recoveries (transfers cancelled and
 *  queued again after the host stopped completing them). Copies min(n, USBPLAY_STATS) values. */
void usbplay_stats(long long* out, int n);

/** Stop streaming: cancel all transfers, wait for their callbacks, join the event thread.
 *  Safe to call when not streaming. */
void usbplay_stop(void);

/** Ask the kernel to bind its driver to [iface] of the usbfs [fd] again (USBDEVFS_CONNECT).
 *  Call after the interface was released. Needs no open libusb handle.
 *  Returns >= 0 on success, -errno on failure. */
int usbplay_reattach(int fd, int iface);

/** usbplay_stop, then close the libusb handle. The fd is not closed. Safe to repeat. */
void usbplay_close(void);

/** Last error message, "" if none. */
const char* usbplay_last_error(void);

/** Last native log lines (start plan, warnings, errors), oldest first, one per line as
 *  "<epoch ms>|<I/W/E>|<text>". Copies at most cap-1 bytes plus a NUL; returns the length. */
int usbplay_log(char* out, int cap);

#ifdef __cplusplus
}
#endif

#endif  // BITPERFECT_USBPLAY_H
