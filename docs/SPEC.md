# Project: BitPerfect Relay App (Android)

An app that captures audio from other music apps (Apple Music, streaming, players) and relays it to a USB DAC. Capture is this app's method; "bit-perfect" is a symbolic name — delivering the original signal unprocessed on the path after capture (relay). Runs in the background.

> **Decision (2026-10-11, user)**: Bluetooth output is **removed**. Capture keeps the original playing on the default output, and with a BT headset connected that output is the headset itself, so the original and the relay are mixed in the same A2DP stream (real-device test 2026-10-11, `dumpsys media.audio_flinger`). UAPP limits its capture mode to its own USB driver for the same reason (local notes). Re-open only if a public way to mute the speaker path alone is confirmed (2026-10-24).

> **Decision (2026-10-09)**: Capture is this app's method (all target apps). "Bit-perfect" is symbolic — the relay path (after capture) stays unprocessed (no resample · volume · dither · mixing). The source-based (UAPP) redesign (2nd revision) is **discarded**. Update 2026-10-11 (user): during a USB relay the music stream is muted the way UAPP does (local notes), so the original no longer leaks to the phone speaker. The core principle "reject original-sound leakage" is **kept**: capture's inherent original output (also playing on the phone's default output) is a **non-goal** due to stock API limits (track volume 0 ⇒ capture 0) and is not framed as acceptable. Background: `docs/archive/qwen-era/PROGRESS.qwen.md` (2026-10-09 entries).

## Basics
- Kotlin (UI/service), C++17 (audio engine, NDK). Jetpack Compose, Material 3
- minSdk 33, targetSdk 36, compileSdk 37 (2026-10-09 [AI judgment] 36→37 synced)
- ABI: arm64-v8a (real device) + x86_64 (Android VM tests; add armeabi-v7a if needed) — 2026-10-10 [AI judgment] added x86_64 for VM test (the x86_64 emulator lacked libengine.so → UnsatisfiedLinkError at launch)
- Package name: `com.bitperfect.relay` (final)
- Distribution: direct APK install (because of the runtime library-swap feature)

## Build/run commands
- Build: `./gradlew assembleDebug` (Git Bash; in PowerShell `.\gradlew.bat assembleDebug`)
- adb is not on PATH: `ADB=/c/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- Install: `$ADB install -r app/build/outputs/apk/debug/app-debug.apk`
- Log: `$ADB logcat -s BitPerfect:*`
- USB DAC cannot be tested on the emulator → instruct real-device testing in text, collect the results, then proceed.

## Core behavior rules
- The relay path (after capture) is unprocessed by principle (no resample · volume · dither · mixing · noise). USB: pass through without resampling to match the output rate. The "reject leakage" core principle is **kept**: capture's inherent original output is a **non-goal** due to stock API limits and is not acceptable (details in RULES.md).
- Relay only when **both** one source app and one output device are selected. If either is deselected, stop immediately.
- If the source app's format changes during relay, restart the pipeline with the new format.
- On USB unplug or capture failure: auto-stop + show the reason in the notification/screen.

## Architecture
```
app/
  ui/            Compose screens (Main, Settings), ViewModel
  data/          per-app settings (Room or DataStore), library version info
  service/       RelayService (foreground service), state management
  capture/       AudioPlaybackCapture wrapper
  output/usb/    USB DAC discovery, descriptor parsing, permission, native bridge
  libs/          library version query/download/verification/apply
  cpp/           native audio engine
    engine/      ring buffer, format conversion, pipeline control (stable C ABI)
    usb/         libusb-based UAC1/UAC2 isochronous output
```

### 1. Capture (capture/)
- After MediaProjection consent, `addMatchingUid(source app UID)` — that app's audio only. usage: MEDIA, GAME, UNKNOWN.
- `AudioRecord`: `ENCODING_PCM_FLOAT`, stereo, sample rate from the per-app stored value.
- Float→int conversion in the engine. No processing · dither. Test-verify that the 24bit original is restored as-is when volume 1.0 · rates match.
- Android 14+ token is single-use. Order: consent → start foreground service → use token.

### 2. Service (service/)
- Type: `mediaProjection`. Permissions: `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `RECORD_AUDIO`, `WAKE_LOCK`.
- Notification: "Apple Music → external DAC · 24bit/96kHz" format + stop button.
- First run: prompt to disable battery optimization.
- State shared with the UI via `StateFlow` (idle / relaying / error+reason).

### 3. USB DAC (output/usb/, cpp/usb/)
- Find audio-class devices + permission via `UsbManager` → pass the fd to native, connect with `libusb_wrap_sys_device` (`LIBUSB_OPTION_NO_DEVICE_DISCOVERY`).
- Detach the kernel driver with `claimInterface(force=true)`.
- Sample rate: UAC1 endpoint SET_CUR, UAC2 clock source SET_CUR. Isochronous transfer, adjusted by feedback.
- No resampling · mixing · volume. Implementation order: UAC2 first, then UAC1.
- **Descriptor parsing · DSD · power · UI item display details: `docs/ref/usb-dac.md`**

### 4. Bluetooth: removed (decision 2026-10-11, see top)

## Reference docs (read only when working on that part)
- `docs/ref/usb-dac.md` — USB descriptor parsing, DSD, power, DAC item display
- `docs/ref/ui-spec.md` — UI layout, output device items, accessibility spec
- `docs/ref/libs.md` — settings-screen library swap, download/apply structure, CI

## Development stages
1. Project skeleton: Gradle, Compose, NDK/CMake, empty engine, log tag
2. Capture test: save the specified app's audio to WAV + instructions for bit-comparing with the original
3. Adopt the capture method — capture is the app's method (source-based switch discarded). Keep the relay path (after capture) unprocessed (2nd revision discarded)
4. USB output test: play a WAV file directly to the DAC (UAC2 → UAC1)
5. Connect capture + USB output, foreground service
6. Bluetooth path (built in the Qwen era; removed 2026-10-11)
7. Main UI, per-app settings storage, accessibility
8. DAC info display (UAC version, max format, DSD, power)
9. Settings screen, library swap (local load; GitHub Actions/download enabled when the repo is provided)
10. Dropped (user, 2026-10-24): optional upsampling. Planned on 2026-10-11 as a later item; never started.