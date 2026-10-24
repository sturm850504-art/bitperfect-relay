# USB Audio Class fact sheet (UAC1 / UAC2 playback)

Verified facts for the USB output path. Each section names its source. Written 2026-10-11 by Claude.

Sources (fetched 2026-10-11, torvalds/linux master unless noted):
- K-a1: `include/uapi/linux/usb/audio.h` (UAC1 constants and structs)
- K-a2: `include/linux/usb/audio-v2.h` (UAC2 constants and structs)
- K-clk: `sound/usb/clock.c` (rate set/get, clock validity, selector)
- K-ep: `sound/usb/endpoint.c` (packet sizing, feedback parsing)
- K-hlp: `sound/usb/helper.c` (data interval)
- K-dev: `drivers/usb/core/devio.c` (usbfs: claim rules, iso URB limits)
- L: vendored libusb 1.0.27 `app/src/main/cpp/usb/libusb/libusb.h`, `os/linux_usbfs.c`
- A: Android SDK stub `platforms/android-37.0/android.jar` via `javap`

## 1. Descriptors

All multi-byte fields little-endian. Every descriptor: [0] bLength, [1] bDescriptorType. Types: 0x01 device, 0x02 configuration, 0x04 interface, 0x05 endpoint, 0x0B interface association, 0x24 CS_INTERFACE, 0x25 CS_ENDPOINT.

Interface (9 bytes): [2] bInterfaceNumber, [3] bAlternateSetting, [4] bNumEndpoints, [5] class (audio 0x01), [6] subclass (0x01 AudioControl, 0x02 AudioStreaming), [7] protocol (0x00 UAC1, 0x20 UAC2).

Endpoint (7 bytes, 9 in UAC1): [2] bEndpointAddress (bit 7 = IN), [3] bmAttributes (bits 1..0 transfer type, 1 = isochronous; bits 3..2 sync type: 1 async, 2 adaptive, 3 sync; bits 5..4 usage: 0 data, 1 feedback, 2 implicit feedback), [4..5] wMaxPacketSize (bits 10..0 bytes per transaction, bits 12..11 extra transactions per microframe), [6] bInterval, UAC1 only: [7] bRefresh, [8] bSynchAddress.

UAC2 AudioControl CS_INTERFACE (K-a2):
- Input Terminal 0x02: [3] bTerminalID, [4..5] wTerminalType, [6] bAssocTerminal, [7] bCSourceID.
- Clock Source 0x0A: [3] bClockID, [4] bmAttributes, [5] bmControls, [6] bAssocTerminal, [7] iClockSource.
- Clock Selector 0x0B: [3] bClockID, [4] bNrInPins, [5..] baCSourceID[bNrInPins].
- Clock Multiplier 0x0C: [3] bClockID, [4] bCSourceID.

UAC2 control bits (K-a2 `uac_v2v3_control_is_readable/writeable`): each control N uses 2 bits at position (N-1)*2. Readable = bit 0 of the pair, writeable = bit 1. Clock Source controls: N=1 sampling frequency (`UAC2_CS_CONTROL_SAM_FREQ`), N=2 clock valid (`UAC2_CS_CONTROL_CLOCK_VALID`).

UAC1 AudioStreaming: AS_GENERAL 0x01: [3] bTerminalLink, [4] bDelay, [5..6] wFormatTag (0x0001 PCM). FORMAT_TYPE 0x02 Type I: [3] bFormatType, [4] bNrChannels, [5] bSubframeSize, [6] bBitResolution, [7] bSamFreqType; 0 = continuous ([8..10] min, [11..13] max), else that many 3-byte rates from [8].

UAC1 CS_ENDPOINT EP_GENERAL (type 0x25, subtype 0x01, 7 bytes; K-a1 `uac_iso_endpoint_descriptor`): [3] bmAttributes: 0x01 sampling frequency control, 0x02 pitch control, 0x80 MaxPacketsOnly (fill to max); [4] bLockDelayUnits, [5..6] wLockDelay.

UAC2 CS_ENDPOINT EP_GENERAL (8 bytes): [3] bmAttributes (0x80 MaxPacketsOnly), [4] bmControls (pitch, overrun, underrun), [5] bLockDelayUnits, [6..7] wLockDelay. UAC2 has no endpoint sampling-frequency control. Linux (`sound/usb/stream.c` `parse_uac_endpoint_attributes`) takes the first CS_ENDPOINT of the alt setting with bLength >= 7 and subtype 0x01; for UAC2 it keeps only bit 0x80.

UAC2 AudioStreaming: AS_GENERAL 0x01: [3] bTerminalLink, [4] bmControls, [5] bFormatType, [6..9] bmFormats (bit 0 PCM, bit 31 raw data `UAC2_FORMAT_TYPE_I_RAW_DATA`), [10] bNrChannels, [11..14] bmChannelConfig, [15] iChannelNames. FORMAT_TYPE 0x02 Type I is 6 bytes: [3] bFormatType, [4] bSubslotSize, [5] bBitResolution. UAC2 descriptors carry no sample rates.

Parser in the app: `output/usb/UacDescriptors.kt` (unit-tested).

## 2. Control requests

Request codes: UAC1 `UAC_SET_CUR` 0x01, `UAC_GET_CUR` 0x81 (K-a1). UAC2 `UAC2_CS_CUR` 0x01, `UAC2_CS_RANGE` 0x02 (K-a2). bmRequestType: 0x21 class/interface/OUT, 0xA1 class/interface/IN, 0x22 class/endpoint/OUT, 0xA2 class/endpoint/IN.

UAC2 (all addressed to the AudioControl interface; wIndex = entityId << 8 | AC interface number; K-clk):
- Set rate: 0x21, CUR, wValue 0x0100 (SAM_FREQ << 8), 4-byte rate. Kernel sends it only if the clock source's SAM_FREQ control is writeable; then reads back with GET CUR.
- Get rate: 0xA1, CUR, wValue 0x0100, 4 bytes.
- Rate ranges: 0xA1, RANGE, wValue 0x0100. Reply: [0..1] wNumSubRanges, then per subrange 12 bytes: dMIN, dMAX, dRES (u32 each). Kernel reads 2 bytes first, then the full length 2 + 12*n (`format.c`). Decoder: `output/usb/UacRates.kt`.
- Clock valid: 0xA1, CUR, wValue 0x0200, 1 byte (nonzero = valid). Only if readable; if not readable the kernel assumes valid.
- Clock selector current pin: 0xA1, CUR, wValue 0x0100 (`UAC2_CX_CLOCK_SELECTOR` << 8), wIndex selectorId << 8 | AC interface, 1 byte, 1-based pin.
- Kernel order (`set_sample_rate_v2v3`): find a valid clock source along the path from the terminal's bCSourceID; GET CUR; if already equal skip SET; else SET CUR then GET CUR.

UAC1 (K-clk `set_sample_rate_v1`):
- Set rate: 0x22, SET_CUR, wValue 0x0100 (`UAC_EP_CS_ATTR_SAMPLE_RATE` << 8), wIndex = data endpoint address, 3-byte rate. Kernel sends it only if the CS_ENDPOINT bmAttributes has bit 0x01.
- Read back: 0xA2, GET_CUR, same wValue/wIndex, 3 bytes. Many devices fail or return 0; the kernel ignores read errors.

## 3. usbfs rules on Android (K-dev, A)

- A control request with interface recipient checks wIndex & 0xFF as the interface number; with endpoint recipient it maps wIndex & 0xFF to its interface. If this fd has not claimed that interface, usbfs tries to claim it and fails while a kernel driver (snd-usb-audio) holds it. So the AudioControl interface must be claimed too, not only the streaming interface.
- Iso URB submit also requires the endpoint's interface to be claimed by this fd. Claims are per fd: a claim made through Android `UsbDeviceConnection.claimInterface(intf, true)` covers libusb transfers on the same fd (`getFileDescriptor()` wrapped with `libusb_wrap_sys_device`).
- `force = true` detaches the kernel driver. Detaching snd-usb-audio removes the Android USB audio route for that device.
- Rebinding the kernel driver: ioctl `USBDEVFS_IOCTL` with `struct usbdevfs_ioctl { ifno, ioctl_code = USBDEVFS_CONNECT, data = NULL }` (NDK `linux/usbdevice_fs.h`). The kernel calls `device_attach` only if no driver is bound to that interface, else `-EBUSY`; so release the interface first. Works on any usbfs fd, no claim needed.
- Iso URB: 1 to 128 packets per URB, each packet at most 98304 bytes.
- Android API (A): `UsbDeviceConnection.getRawDescriptors(): ByteArray`, `controlTransfer(type, request, value, index, buffer, length, timeoutMs): Int` (bytes transferred, negative on failure), `claimInterface(UsbInterface, Boolean)`, `releaseInterface(UsbInterface)`, `setInterface(UsbInterface)` (selects that interface's alternate setting), `getFileDescriptor()`. `UsbDevice.getInterface(i)` lists every (interface, alternate setting) pair; match by `getId()` and `getAlternateSetting()`.

## 4. Speed, intervals, packet sizing (K-ep, K-hlp)

- Speed from libusb: `libusb_get_device_speed(libusb_get_device(handle))`. For a wrapped fd libusb uses the `USBDEVFS_GET_SPEED` ioctl (L `linux_usbfs.c`).
- Full speed: 1 ms frames. High speed and faster: 125 us microframes.
- Data interval (log2, in (micro)frames): high speed and up, bInterval 1..4 gives bInterval - 1; otherwise 0. Full speed: 0.
- Packets per second: pps = 1000 >> interval (full speed) or 8000 >> interval (high speed).
- Nominal rate in Q16.16 frames per (micro)frame: full speed `freqn = ((rate << 13) + 62) / 125`; high speed `freqn = ((rate << 10) + 62) / 125`. Use 64-bit math.
- Max packet bytes = wMaxPacketSize bits 10..0 times (1 + extra transactions). Kernel: freqmax = freqn * 1.5; if that does not fit in max packet, freqmax is reduced to what fits.
- No feedback (adaptive or synchronous): packsize[0] = rate / pps, packsize[1] = ceil(rate / pps), accumulator += rate % pps; when accumulator >= pps, subtract pps and send packsize[1], else packsize[0].
- Async with feedback: freqm starts at freqn. Per packet: `phase = (phase & 0xffff) + (freqm << interval)`; frames = min(phase >> 16, max frames per packet).
- UAC1 MaxPacketsOnly (bmAttributes 0x80): every packet is max size (kernel `fill_max`).
- Every packet carries whole frames; frame bytes = channels * subslot bytes.

## 5. Feedback endpoint (K-ep `snd_usb_handle_sync_urb`)

- A separate isochronous IN endpoint (usage type 1, or for UAC1 the address in bSynchAddress). Never appended to OUT packets.
- Spec formats: full speed 10.14 in 3 bytes (frames per frame), high speed 16.16 in 4 bytes (frames per microframe). Many devices deviate, so the kernel auto-detects:
  - Ignore the packet if its status is not OK or its length is under 3. Value f = little-endian u32 of the first bytes, masked with 0x00FFFFFF if length is 3, else 0x0FFFFFFF. Ignore f == 0.
  - First valid value: shift f left while f < freqn - freqn/4, right while f > freqn + freqn/2; remember the shift. Later values: apply the remembered shift.
  - Accept if freqn - freqn/8 <= f <= freqmax, then freqm = f. Otherwise forget the shift so the next value is detected again.
- Polling interval (log2 frames): bRefresh if the endpoint is 9 bytes and bRefresh is 1..9; else full speed 1; else bInterval - 1 for bInterval 1..16; else 3.

## 6. libusb async iso API (L)

- `libusb_iso_packet_descriptor` has only `length`, `actual_length`, `status` (no offset field: packet i starts after the lengths of packets 0..i-1). `libusb_alloc_transfer` does not set `transfer->num_iso_packets`; `libusb_fill_iso_transfer` (returns void) sets it and also overwrites `transfer->length`. So call fill first, then set packet lengths and the real total length.
- `libusb_alloc_transfer(numIsoPackets)`, `libusb_fill_iso_transfer(t, handle, endpoint, buffer, length, numIsoPackets, callback, userData, timeout)`, `libusb_set_iso_packet_lengths(t, len)`, per packet `t->iso_packet_desc[i].length / actual_length / status`, `libusb_get_iso_packet_buffer_simple(t, i)` (only valid when all packets have equal length; with variable OUT lengths, compute offsets by summing lengths).
- A transfer finishes by calling its callback from inside `libusb_handle_events*` on the thread that runs the event loop. `t->status`: `LIBUSB_TRANSFER_COMPLETED`, `CANCELLED`, `NO_DEVICE`, `ERROR`, and others. For iso, check each packet status too.
- Resubmit from the callback with `libusb_submit_transfer`. Cancel with `libusb_cancel_transfer`; the callback still runs (status `CANCELLED`); free only after the callback ran.
- Event loop: `libusb_handle_events_timeout_completed(ctx, &tv, nullptr)`; `libusb_interrupt_event_handler(ctx)` wakes it.
- Android: `libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY)` before `libusb_init` (stored as a default and applied to every new context, L `core.c`), then `libusb_wrap_sys_device(ctx, fd, &handle)`. Setting it after `libusb_init` is too late: discovery already ran.
- In this app libusb is reached only through the `g_usb` function table (`cpp/dl/dllib.h`), so new libusb calls need a table entry.
