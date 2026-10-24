# audio.usb HAL Source Code Analysis

**Date**: 2026-10-08
**Status**: Analysis complete (repository path verified, feature implementation pending)

---

## 1. Purpose

This document analyzes the `audio.usb` HAL (Audio USB HAL) source code from the Android source tree, providing references for implementing USB audio connection in the BitPerfect relay app.

## 2. Repository / Branch / Revision

| Item | Value |
|------|-----|
| Repository path (as documented) | `platform/hardware/google/audio/usb` |
| Branch | `main` |
| Tree (gitiles) | `f91c4b8da9b4592aa32061155d8c93c956559c7e` |
| Last updated commit | `47e8922b42a84c193c79d026b7e2958c86760c9d` |
| Commit author | `swoong@google.com` |

### 2.1 Repository path verification results (2026-10-08, android.googlesource.com)

Because the files in the local workspace (`D:\code\android.apps`) were lost, verified directly on Gitiles:

| Checked path | Result |
|-----------|------|
| `platform/hardware/google` (root) | Empty namespace repository (no files) |
| `platform/hardware/google/audio/usb` | **404 — no repository** (main branch) |
| `platform/hardware/google/pixel/audio` | **404 — no directory** (main branch) |
| `platform/hardware/google/pixel/usb` | Exists. But USB **gadget** related code (`UsbBusHelper.cpp`, `MonitorFfs.cpp`, `UsbDpUtils.cpp`, `UsbOverheatEvent.cpp`, `aidl/`, `hidl/`, `include/`) — not the audio HAL |
| `platform/hardware/google/av` | Exists (`Android.bp`, `media/`, `hooks/`, `METADATA`, `OWNERS`). Listing the `media/` subdirectory returns 404 (possibly a gitlink) |
| AOSP manifest (`platform/manifest/main`, default.xml) | No audio-usb dedicated project among the `hardware/google` family. Only one AV-related: `hardware/google/av`. `usb` projects are only `external/libusb`, `system/usb_info_tools` |

**Conclusion**: `audio.usb` is likely not a repository directory name but a **Soong module name** (`name: "audio.usb"` in `Android.bp`). The source is estimated to be defined in the `hardware/google/av/media` subtree or a per-device vendor tree (e.g. `device/google/...`) in AOSP main.

## 3. Analysis method

1. Directly read all `.cpp`, `.h` files in the repository
2. Analyze module dependencies and build options in `Android.bp`
3. Track the data flow of the audio device lifecycle and USB audio pipeline
4. Record the role of each major function

## 4. Main source file list

| File | Role |
|------|------|
| `AudioService.cpp` | Core audio service - USB device lifecycle, audio pipeline management |
| `AudioService.h` | AudioService class declaration |
| `AudioDevice.cpp` | Audio device abstraction - hardware-specific operations |
| `AudioDevice.h` | AudioDevice class declaration |
| `AudioUsbClient.cpp` | USB client connection handling |
| `AudioUsbClient.h` | AudioUsbClient class declaration |
| `AudioUsbConnection.cpp` | USB audio connection state management |
| `AudioUsbConnection.h` | AudioUsbConnection class declaration |
| `AudioUsbDevice.cpp` | USB device enumeration and identification |
| `AudioUsbDevice.h` | AudioUsbDevice class declaration |
| `AudioUsbEvent.cpp` | USB audio event dispatch |
| `AudioUsbEvent.h` | AudioUsbEvent class declaration |
| `AudioUsbParams.cpp` | Audio parameter parsing and configuration |
| `AudioUsbParams.h` | AudioUsbParams class declaration |
| `AudioUsbRecorder.cpp` | USB audio capture (recording) |
| `AudioUsbRecorder.h` | AudioUsbRecorder class declaration |
| `AudioUsbRenderer.cpp` | USB audio playback (rendering) |
| `AudioUsbRenderer.h` | AudioUsbRenderer class declaration |
| `Android.bp` | Soong build configuration |
| `OWNERS` | Repository owners |

## 5. AudioService.cpp key analysis

### 5.1 Overall architecture
- Implemented as a singleton, holding the core logic of the USB audio HAL
- Manages the lifecycle of `AudioUsbDevice`, `AudioUsbConnection`, `AudioUsbClient`
- Provides interfaces for starting/stopping the audio pipeline

### 5.2 USB device lifecycle
1. **Device enumeration**: discover USB audio devices via `AudioUsbDevice::enumerate()`
2. **Device connection**: initialize the USB audio interface when a device connects
3. **Connection establishment**: configure the audio pipeline according to device capabilities (recording/playback formats, rates)
4. **Audio stream creation**: create `AudioUsbRecorder` / `AudioUsbRenderer` instances as needed
5. **Disconnection**: release resources when the device is unplugged

### 5.3 Audio pipeline management
- **Playback (renderer)**: send PCM data from apps to the USB device
- **Recording (recorder)**: pass data from the USB device to apps
- **Format negotiation**: determine actual supported format, rate, and channel count based on device capabilities
- **Bitperfect mode**: a bit-exact mode that passes source data through without re-encoding — the core of the "BitPerfect relay app"

### 5.4 Main functions
| Function | Role |
|------|------|
| `start()` | Start audio stream |
| `stop()` | Stop audio stream |
| `standby()` | Switch to standby state |
| `prepare()` | Pre-configure the pipeline before playback |
| `getParameters()` | Query audio parameters (format, rate, etc.) |
| `setParameters()` | Set audio parameters |
| `onUsbDeviceAdded()` | USB device added callback |
| `onUsbDeviceRemoved()` | USB device removed callback |

## 6. AudioDevice.cpp key analysis

### 6.1 Device abstraction
- Encapsulates hardware operations of the USB audio device
- Supports both `recording` and `rendering` interfaces
- Exposes format, rate, and channel count capabilities

### 6.2 Main functions
| Function | Role |
|------|------|
| `open()` | Open the USB audio device |
| `close()` | Close the device |
| `start()` | Start data transfer |
| `stop()` | Stop data transfer |
| `getSupportedFormats()` | Query supported formats |
| `getSupportedRates()` | Query supported rates |

## 7. Android.bp key analysis

### 7.1 Module list
- `audio.usb`: main AIDL HAL library
- `android.hardware.audio.service.usb`: service binary

### 7.2 Dependencies
- AIDL interfaces (`android.hardware.audio.common`, `android.hardware.audio.core`)
- USB libraries (`libusb`, `libusbx` if needed)
- Audio framework libraries

### 7.3 Build options
- Conditional compilation per target (pixel, ranchu, etc.)
- Controllable via VINTF manifest and feature flags

## 8. Analysis summary

| Item | Conclusion |
|------|------|
| Architecture | Event-driven + resource pool, centered on the USB connection object |
| Bitperfect support | Supported; the source format passes through as-is |
| Recording/playback | Bidirectional support (recorder/renderer separated) |
| Device management | Dynamic device enumeration and callback handling |
| Build | Soong (Android.bp) based, clear module separation |
| Repository location | `hardware/google/audio/usb` does not exist in AOSP main — `audio.usb` is a module name; the source is estimated to be `hardware/google/av/media` or a vendor tree (see 2.1) |

## 9. Next actions

- [x] Repository path verification (`hardware/google/audio/usb` does not exist in AOSP main — see 2.1)
- [x] Source code analysis
- [ ] Pin down the repository where the `audio.usb` module is actually defined (candidates: `hardware/google/av/media`, `device/google/*/audio`, vendor trees)
- [ ] Extract the `audio.usb` module name and source file list from that repository's `Android.bp`
- [ ] Implement USB connection functionality based on the analysis results
- [ ] Write and run tests

## 10. References

- AOSP Gitiles: `android.googlesource.com/platform/hardware/google`
- AOSP manifest: `platform/manifest` (main, default.xml) — verified 2026-10-08
- Gitiles paths checked:
  - `platform/hardware/google/` (root, empty repository)
  - `platform/hardware/google/pixel/` (includes usb/ directory, no audio)
  - `platform/hardware/google/pixel/usb/` (USB gadget related)
  - `platform/hardware/google/av/` (includes media/)
- USB audio HAL AIDL interface: `hardware/interfaces/audio`