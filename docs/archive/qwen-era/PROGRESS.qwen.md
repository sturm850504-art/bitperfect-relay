# Progress log

New entries: prepend below this line (top of file).

## 2026-10-11 Stage 9 (engine rework) — runtime dlopen library swap implemented, static-verified; real-device test pending

- Current stage: stage 9 **complete in code**. The engine no longer links libusb/soxr; it dlopen-swaps them at runtime with auto-restore on failure (the `docs/ref/libs.md` contract: verify → stop relay → unload → load new → auto-restore → resume). The settings-screen entry below (same date) is now superseded on its "pending" items: picking a version (or bundled default) now **applies immediately**, and restart re-applies the persisted selection.
- C++ / NDK:
  - `app/src/main/cpp/dl/dllib.h` (new) — `UsbLibTable` (21 pointers) + `SoxrLibTable` (4 pointers) using the **real vendored signatures** (libusb 1.0.27 `libusb.h`, soxr 0.1.3 `soxr.h`) — not `void*`, so call sites stay type-correct C++. Tables are C++ linkage (they hold `std::string`); the 4 `dllib_*` API functions are `extern "C"`.
  - `app/src/main/cpp/dl/dllib.cpp` (new) — `dllib_init(nativeLibDir)` loads the bundled defaults from jniLibs; `dllib_apply(key, path)` unloads the current `.so`, loads the new one, rebinds pointers; on load failure it **reloads the previous version** and reports `<reason> — previous version restored` (or `… restore also FAILED` if there was nothing/it failed). `dllib_loaded_path` / `dllib_last_error` for honest status. All under a single mutex; `RTLD_NOW | RTLD_LOCAL`.
  - `usb/usbplay.cpp` — 20 shim `#define`s map libusb calls onto `g_usb.p_*`; null-pointer guard at init reports "libusb 미로드 (dlopen 실패)".
  - `bt/btplay.cpp` — 4 shims onto `g_soxr.p_*`; null guard in the resample branch.
  - `engine/engine.{h,cpp}` — `engine_apply_lib(key, path)` quiesces all outputs first (`usbplay_stop/stream_stop/close`, `btplay_stop`) then calls `dllib_apply`. JNI: `nativeInit(nativeLibDir)`, `nativeApplyLib`, `nativeLibActivePath`, `nativeLibLastError`.
  - `CMakeLists.txt` — `usb` + `soxr` are now **SHARED** targets (`libusb.so`/`libsoxr.so` packaged into APK jniLibs); `engine` links only `oboe ${log-lib} ${dl-lib}` and reaches libusb/soxr exclusively through the tables.
- Kotlin / UI:
  - `Engine.kt` — `init(nativeLibDir)`, `applyLib(key, path)`, `libActivePath(key)`, `libLastError()`; `MainActivity.kt` re-applies persisted selections at startup (nothing streaming, main thread safe).
  - `LibraryManager.kt` — `selectAndApply` / `applySelection(key, version)` (null version = bundled default) persist **and** apply.
  - `SettingsScreen.kt` — pick/revert now apply immediately and show per-library ok/fail status (`적용됨` / `적용 실패 — <detail>`); revert button returns to bundled. `strings.xml` copy updated (honest, no "미구현" claim left).
  - `LibraryCatalog.kt` KDoc — runtime-swap semantics.
- Verified (fresh run this session, static checks only):
  - `./gradlew assembleDebug` → **BUILD SUCCESSFUL** (arm64-v8a + x86_64).
  - `llvm-nm -D libengine.so`: **zero** undefined libusb/soxr symbols; `dlopen/dlsym/dlclose/dlerror` present (from libc) → linkage is dlsym-only, as designed.
  - `libusb.so` / `libsoxr.so` present in APK `jniLibs` for both ABIs; all 21 bound libusb symbols and all 4 bound soxr symbols **exported** by the built `.so` files.
- Unverified (honestly — needs the user's test loop, per the standing plan):
  - Runtime behavior on a real device/VM: bundled-load logs (`BitPerfectLibs`: "libusb bound (21 symbols)", "soxr bound (4 symbols)"), actual swap of a user-placed `.so`, and the auto-restore path with a bad `.so`.
  - USB + BT relay still stream correctly now that they go through the tables (expected — same symbols — but not exercised).
  - ABI-compatibility caveat (unchanged, by design): a local `.so` must expose the same symbol set/signatures (stable C ABI); symbol presence is checked at bind time, full behavior is the user's responsibility.

## 2026-10-11 Stage 9 — Settings screen + library catalog / local discovery done (dlopen-apply pending)

- Current stage: stage 9 (settings + library swap) — **settings screen, catalog, local-candidate discovery, SHA-256, selection recording: code done, build verified (both ABIs)**. The actual **runtime dlopen load/apply is NOT yet wired** (engine still statically links libusb/soxr). This is stated honestly in the UI — not dressed up as working. So stage 9 is **partially done**; the engine dlopen rework is the remaining next unit.
- Done (6 files — the settings screen is one cohesive feature, marginally over the ≤5 soft line):
  - `app/src/main/java/com/bitperfect/relay/data/LibraryCatalog.kt` (new) — `RelayLibrary` + `LibraryCatalog.all` (full OSS list: libusb 1.0.27, libsoxr 0.1.3, oboe 1.8.0 + managed/toolchain deps; versions from `gradle/libs.versions.toml`, licenses). `swappable=true` marks the *designated* local-load targets per `docs/ref/libs.md` (libusb, soxr). Comments state honestly that the dlopen-apply is a pending engine update.
  - `app/src/main/java/com/bitperfect/relay/data/LibraryManager.kt` (new) — discovers user-placed `.so` under `files/libs/<key>/<version>/<abi>/` for the running ABI; SHA-256 integrity fingerprint (MessageDigest, 64 KB stream, null on read failure); persists per-library selection (`sel_<key>`); `selected` StateFlow; file I/O off the main thread.
  - `app/src/main/java/com/bitperfect/relay/ui/SettingsScreen.kt` (new) — full catalog (name/version/license + "로컬 로드 대상"/"읽기 전용" badge); load-target libs show local candidates (size + truncated SHA-256 + recorded highlight); TalkBack on every row/button; 48dp targets; no Icons dependency (TextButton back).
  - `app/src/main/java/com/bitperfect/relay/ui/MainScreen.kt` — `onOpenSettings` param + bottom "설정" TextButton (48dp, TalkBack).
  - `app/src/main/java/com/bitperfect/relay/MainActivity.kt` — `showSettings` state; `MainScreen` ⇄ `SettingsScreen` switch; system back via `androidx.activity.compose.BackHandler(enabled = showSettings)`.
  - `app/src/main/res/values/strings.xml` — Stage 9 Korean strings; dead `ui_libs_current`/`ui_libs_size` removed; copy made honest (selection is "기록됨/recorded"; dlopen-apply is "미구현").
- Honest scope (not a dressed-up feature): the swap *mechanism* — dlopen the selected `.so` → SHA-256 verify → stop relay → unload engine → load new version → auto-restore on failure → resume (per `docs/ref/libs.md`) — is **not implemented**. `CMakeLists.txt` statically links libusb + soxr. So selecting a version today only **records** it; it does not change the loaded library. That is the correct, honest increment; the engine rework is a separate stage that needs the VM/real-device verification loop, so it is not forced in here right before the session ends.
- Oboe: C++ (classes + vtable) → not cleanly dlopen-swappable → stays static, shown read-only with the reason. (Honest, per the "no dressing up" rule — even though `docs/ref/libs.md` lists it as a swap target; a C++ dlopen attempt would be a separate design decision.)
- Verified (fresh run this session): `./gradlew assembleDebug` → **BUILD SUCCESSFUL** (both ABIs `arm64-v8a` + `x86_64`); new-code deprecation removed (`Build.CPU_ABI` → `SUPPORTED_ABIS.firstOrNull()`).
- Unverified (honestly):
  - Runtime dlopen load/apply of a local `.so` (not wired; engine statically links).
  - Settings screen rendering + TalkBack on a real device / VM (not exercised this session).
- Next unit (the real "library swap"): engine rework to dlopen libusb + soxr — build them as SHARED in `jniLibs`, resolve the ~30 libusb + 5 soxr symbols, stop→unload→load→restore, SHA-256 verify. Bigger native change (≥5 files); needs its own build + VM/real-device verification. Open real-device batches remain stages 4-6 (USB streaming + BT A2DP).

## 2026-10-11 Stage 8 — DAC info display (UAC version, max format, DSD, power) done

- Current stage: stage 8 (DAC info display) **code done, build verified (both ABIs)**. Runtime display unverified — real device + connected DAC required (user, hardware).
- Done (7 files):
  - `app/src/main/cpp/usb/usbplay.cpp` — **UAC2 Format Type I/II parse fixed**. The previous offsets were wrong for real UAC2 descriptors (subslot@5/bitDepth@6/rates@10 lands inside `bmChannelConfig`/`bBitResolution`, yielding bitDepth 0 and garbage "rates" ≈ 33.5 MHz). Now parsed per the UAC2 spec field order — `bNrChannels@3, bmChannelConfig(4), iChannelNames, bSubframeSize@9, bBitResolution@10, bmFormats(2)@11, bSampleRateCount@13, tSampleRates[](4B)@14+`, min bLength 0x0E; identical field order to the Linux kernel `audio_format_desc` (uapi/sound/asound.h) and to real `lsusb -v` dumps. **DSD flag**: `bmFormats & 0xFF00` — bit 8 is the UAC3 DSD bit and what DSD vendors set on UAC2; bits 2-7 deliberately excluded (UAC3 IEC/AC3 consumer-audio bits, would false-positive on AC3); vendors with unusual bits go into the exception list below. UAC1 branch untouched (already correct).
  - `app/src/main/cpp/usb/usbplay.h` — new additive stable C ABI `usbplay_dac_info(out_uac2, out_bit_depth, out_max_rate_hz, out_dsd)`.
  - `app/src/main/cpp/engine/engine.cpp` — JNI binding `nativeUsbDacInfo` (4 output arrays).
  - `app/src/main/java/com/bitperfect/relay/Engine.kt` — `data class UsbDacInfo(uac2, bitDepth, maxRateHz, dsd)` + `usbDacInfo()` (null if no device open; 0 = unknown).
  - `app/src/main/java/com/bitperfect/relay/ui/MainScreen.kt` — USB device rows now show: format line `"32bit/384kHz"` (+ `" · DSD"` when the descriptor flag **or** the vendor list says DSD), `UacBadge` ("UAC 2.0"/"UAC 1.0" from `bInterfaceProtocol == 0x20`), `PowerIcon` (⚡ = phone power / 🔋 = built-in battery, from `configuration.isSelfPowered`) — all with TalkBack semantics including the power wording (spec: "powered by phone" style). Info is read once per device-list change, only when not `running`, in a single open/close cycle (engine is process-shared).
  - `app/src/main/res/values/strings.xml` — `ui_usb_uac` / `ui_usb_power_phone` / `ui_usb_power_battery` / `ui_usb_format` / `ui_usb_format_dsd` / `ui_usb_device` / `ui_usb_device_full`.
  - `app/src/main/assets/dac_dsd.json` (new) — `[]`; the per-vendor DSD exception list (spec: may start empty). Entry format `"<vendorId>:<productId>"` (decimal).
- Vendor-list convention (for future edits): `dsd = descriptorBitmap || "vendorId:productId" in dac_dsd.json`, checked in `MainScreen.kt` per row.
- Verified (fresh run this session): `bash gradlew assembleDebug` → **BUILD SUCCESSFUL** (40 tasks; CMake ran for **both** `arm64-v8a` and `x86_64`; APK rebuilt 01:52, 37,166,529 B).
- Unverified (honestly, real device required):
  - Descriptor parsing on a physical UAC2 DAC (offsets verified against spec/kernel layout, not a device).
  - DSD bitmap bit conventions on real hardware — vendors vary; that is what the vendor list is for.
  - `isSelfPowered` mapping on real hardware (self-powered ⇒ battery icon).
  - TalkBack reading of the new row descriptions.
- Real-device test (user, hardware required):
  1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  2. Connect the USB DAC, grant USB permission when prompted, open BitPerfect.
  3. Expected in the device list: name + `UAC 2.0`/`UAC 1.0` badge + format line (e.g. "32bit/384kHz") + ⚡/🔋 icon. With TalkBack on: the row reads name, UAC, format and the power wording.
  4. Cross-check the numbers against the DAC's spec sheet (max rate/bits) — if they disagree, report the DAC's make/model; the DSD/vendor list (`assets/dac_dsd.json`) exists exactly for this.
- Next: stage 9 (settings screen + library swap). Real-device batches still open: stages 4-6 (USB streaming + BT).

## 2026-10-11 Stage 6 Phase 3 — A2DP routing wiring (device list, codec query, RelayService BT branch, BT UI) done

- Current stage: stage 6 — Phase 3 (A2DP routing + wiring) **code done, build verified**. Stage 6 as a whole is now code-complete (Phase 1 build ✓, Phase 2 engine ✓, Phase 3 wiring ✓). **Runtime BT behavior unverified — real-device test required (user, hardware).**
- Done (4 files; `Engine.kt` untouched — Phase 2 ABI reused as-is):
  - `app/src/main/java/com/bitperfect/relay/output/bt/BtA2dp.kt` (new) — `connectedDevices(am)` via `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` filtered to `TYPE_BLUETOOTH_A2DP` (id = `AudioDeviceInfo.id` for Oboe `setDeviceId`); `codecInfo(am, deviceId)` — **reflective, best-effort** `getCodecStatus(int)` → active codec name/rates + supported codecs, `null` on any failure; `outRateFor(info, captureRate)` — capture rate if in the codec's rate list (bit-perfect, no soxr), else first reported rate, else honest 48 kHz (`DEFAULT_OUT_RATE_HZ`).
  - `RelayService.kt` — BT branch: step 8 `outputType == "bt"` → `beginBtOutput(id, captureRate)` (resolves out-rate, `Engine.btBegin`, codec name for status; null on `btBegin` failure → `ui_status_bt_fail` → WAV fallback); read loop feeds `Engine.btFeed` and on error → `ui_status_bt_feed_fail` + auto-stop (SPEC: output failure = stop); `finally` + `stopCaptureAndSelf` call `Engine.btStop()` (idempotent); `AudioDeviceCallback` registered in `onCreate`: A2DP device removed while `btOn` → `ui_status_bt_off` + `stopCaptureAndSelf()`; `switchOutput(type, id, rate)` companion + command — stops all sinks then re-routes only (capture keeps running, per SPEC).
  - `MainScreen.kt` — BT tree now lists **connected** A2DP outputs (was: bonded-devices placeholder); `refreshBt()` = devices + best-effort codec for the selected device (or first); deselected device removed → selection cleared; `onBtSelect` → running ? `RelayService.switchOutput("bt", …)` (re-route only) : `onStart()`; `BtCodecDropdown` now read-only/informational (options from `codecInfo`, disabled when empty) — apps cannot force A2DP codec negotiation, the system picks; dead code removed (`BT_CODECS`, `BluetoothNamedDevice`, `BluetoothAdapter` import, BT early-return in `onStart()`).
  - `strings.xml` — dead `ui_bt_stage6` / `ui_status_bt_pending` removed; added `ui_bt_codec_unknown`, `ui_status_bt_on` / `_fail` / `_off` / `_feed_fail` / `_switch` / `_switch_fail` — all conform to the statusReceiver contract (`running` flips only on 재생 시작 / 정지·오류·분리·거부·폴백).
- Stub-jar facts (javap on android-37.0 stub, this session — do not re-verify): **present** — `GET_DEVICES_OUTPUTS`, `getDevices(int)`, `registerAudioDeviceCallback(AudioDeviceCallback, Handler)` (+ unregister), `AudioDeviceInfo.id/type/address/productName`, `TYPE_BLUETOOTH_A2DP`, `onAudioDevicesAdded/Removed`. **absent** — `getCodecStatus`, `BluetoothCodecStatus`, `getAvailableBluetoothCodecs`, `ACTION_AUDIO_DEVICE_CONNECTION_CHANGED` → hence the reflective best-effort codec query, and **no** BT-connection broadcast receiver (UI refresh = ⟳ button + on-selection; auto-stop on disconnect covered by `AudioDeviceCallback`).
- Verified (fresh run this session): `bash gradlew assembleDebug` → **BUILD SUCCESSFUL** (40 tasks), both ABIs.
- Unverified (honestly, real device required):
  - Oboe `setDeviceId` targeting an A2DP `AudioDeviceInfo.id` — engine code is untested at runtime (Phase 2 gap, now reachable).
  - `getCodecStatus` reflection on a real device (API 35+ only; older devices silently get the 48 kHz fallback — by design, `Log.w` + "코덱 정보 없음").
  - Auto-stop on A2DP disconnect (`AudioDeviceCallback.onAudioDevicesRemoved`).
  - soxr VHQ/CR64 resample path end-to-end (no unit tests exist — known gap noted in the Phase 2 entry).
- Next: real-device BT test (user, hardware) — same procedure as the USB-DAC batch below but with a connected A2DP headset: expect "재생 시작 … → 블루투스 (codec)" status, clean audio, and auto-stop on headset disconnect. After stage 6: stage 8 (DAC info display), stage 9 (settings + library swap).

## 2026-10-10 Stage 6 Phase 2 — BT output engine (soxr VHQ/CR64 + Oboe render + stable C ABI) done

- Current stage: stage 6 — Phase 2 (BT runtime engine) **done, build verified (both ABIs)**. Phase 3 (A2DP routing: codec query, device selection, RelayService BT branch, codec UI) not yet coded.
- Done (5 files):
  - `app/src/main/cpp/bt/btplay.h` (new) — stable C ABI: `btplay_begin(in_hz, out_hz, device_id)` / `btplay_feed(samples, frame_count)` / `btplay_stop()` / `btplay_ok()` / `btplay_last_error()`. Same contract as `usbplay_stream_*`: stereo float32 interleaved, ~4 s latency cap (drop oldest), shortfall filled with silence. `device_id` 0 = default output (A2DP device selection comes from Kotlin in Phase 3).
  - `app/src/main/cpp/bt/btplay.cpp` (new) — feed `std::deque` + mutex; the Oboe render callback is the **only** soxr user (thread-safe by construction). Equal rates → pass-through untouched (bit-perfect); different rates → libsoxr VHQ/CR64 resamples to the A2DP codec rate (SPEC §4: the only allowed conversion; the system does codec encoding). Oboe stream built with `SampleRateConversionQuality::None` so it never resamples on its own. `btplay_stop()` = requestStop + bounded wait for `Stopped` + close (no callbacks after close returns) + `soxr_delete` + queue clear; safe to call repeatedly; `btplay_begin` re-calls stop first for restart safety.
  - `app/src/main/cpp/CMakeLists.txt` — `bt/btplay.cpp` added to the `engine` target (soxr/oboe linkage already in place since Phase 1).
  - `app/src/main/cpp/engine/engine.cpp` — 5 new JNI bindings (`nativeBtBegin` / `nativeBtFeed` / `nativeBtStop` / `nativeBtOk` / `nativeBtLastError`), mirroring the USB ones.
  - `app/src/main/java/com/bitperfect/relay/Engine.kt` — `btBegin` / `btFeed` / `btStop` / `btOk` / `btLastError` wrappers + external declarations.
- Vendored API facts learned this session (differ from upstream docs — do not re-learn):
  - soxr v0.1.3: `soxr_error_t` is `char const *` — **NULL = no error, non-NULL = the error message string itself**. There is no `SOXR_NO_ERROR` enum; `soxr_strerror` is just a `((e)?(e):"no error")` macro.
  - Vendored Oboe: `AudioStreamBuilder` setters return `AudioStreamBuilder *` (chain with `->`); `Result::OK` (not `Ok`); `AudioFormat::Float` (not `Float32`); `StreamState::Stopped`; the error callback is `void onErrorBeforeClose(AudioStream*, Result)` (2 args, no message); human-readable code via `oboe::convertToText()`.
- Verified (fresh run this session): `bash gradlew assembleDebug` → **BUILD SUCCESSFUL**; `btplay.cpp.o` rebuilt for **both** `arm64-v8a` and `x86_64` (23:08, same minute as the APK); APK `app-debug.apk` (37,780,228 B) rebuilt.
- Unverified / not done (honestly):
  - **No runtime BT test possible in this phase** — the engine is not yet reachable from the UI: Phase 3 must wire the A2DP device list, `getCodecStatus()` codec rate, `setDeviceId` pass-through, the RelayService BT branch and the codec selection UI. That is the next stage (new chat).
  - **No unit tests exist for the resample path.** RULES asks for audio-processing unit tests, but `app/src/test/` holds none today — the soxr/Oboe path is native and the project has no host-toolchain test target. [AI judgment: noted as a known gap rather than expanding scope into a new test infrastructure this unit; a host-side soxr test target is a candidate for a dedicated stage.]
- Real-device test (user, hardware required): none runnable for this phase (BT not wired to the UI yet — see above). The stage-7 real-device USB-DAC procedure below remains the open hardware batch.
- Remaining: stage 6 **Phase 3** (A2DP routing + wiring + codec dropdown), then real-device BT test (user). After stage 6: stage 8 (DAC info display), stage 9 (settings + library swap).

## 2026-10-10 Stage 6 Phase 1 committed — soxr + Oboe build integration verified (both ABIs)

- Current stage: stage 6 — Phase 1 (build-only library integration) **done and committed**. Phase 2 (BT runtime: soxr init VHQ/CR64 + Oboe output thread + stable C ABI) and Phase 3 (A2DP routing/wiring) not yet coded.
- Verified this session (fresh run): `bash gradlew assembleDebug` → BUILD SUCCESSFUL (40 tasks, exit 0), APK rebuilt 2026-10-10 20:34.
- Artifacts (both `arm64-v8a` + `x86_64`): `libengine.so` (in APK: 618880 B / 603136 B), `libsoxr_static.a`, `oboe/liboboe.a`, `soxr-config.h`, `liblibusb_core.a` — satisfies all of `docs/testplans/STAGE6-BT-BUILD.md` steps 1–2.
- soxr-config.h (arm64): `WITH_CR64 1`, `WITH_CR32 0`, `WITH_VR32 0`, `WITH_PFFFT 0` — exactly the plan's expected values (CR64-only engine).
- Committed: `app/src/main/cpp/CMakeLists.txt` (soxr_static target + `add_subdirectory(oboe)` + engine links), vendored `soxr/` (v0.1.3) + `oboe/`, `.gitignore` (exclude `vendor-dl/` tarball cache + `build-stage6.log`), `docs/testplans/STAGE6-BT-BUILD.md`.
- Not run: the Gemini STAGE6-BT-BUILD evidence run — optional here since the build + artifact inspection above already covers its checks; the plan stays available for an independent evidence pass if wanted.
- Shell note (for future sessions): from Git Bash, `gradlew.bat` is not directly executable — use `bash gradlew ...` or `cmd //c "D:\\code\\android.apps\\gradlew.bat ..."` (full path).
- Remaining: stage 6 Phase 2 (BT output engine: Oboe + libsoxr runtime glue) — start in a **new chat** (RULES: one stage per chat). Then Phase 3, then real-device BT test (user, hardware required).

## 2026-10-10 Cleanup: remove dead `ui_usb_empty` string

- Current stage: stage 6 in progress (Phase 1 library integration uncommitted; Phase 2/3 not yet coded).
- Done: removed `ui_usb_empty` from `app/src/main/res/values/strings.xml` — zero code references (grep whole repo; only `ui_usb_connect_first` is used, `MainScreen.kt:492`). `gradlew.bat assembleDebug` → BUILD SUCCESSFUL (both ABIs).
- Remaining: stage 6 Phase 2 (BT output engine: Oboe + libsoxr) is the next code unit.
- Unverified: none (resource removal only).

## 2026-10-10 STAGE7-UI-VM: run 1640 = PASS — evidence verified by Claude; stage 7 committed

- Gemini run `2026-10-10_1640` (`docs/reports/2026-10-10_1640_STAGE7-UI-VM/`): **Overall PASS, steps 0–10 all PASS, Deviations NONE**. `APP_LABEL` = `Calendar`; REUSE path (running `emulator-5554` reused; `HypervisorPresent`=True, guest cpuinfo = i5-13600KF, not QEMU).
- **Claude evidence review (independent — the actual XML files, not the executor's summary):**
  - `04_ui.xml`: all 13 initial strings present incl. `USB DAC를 연결하세요 (OTG)` ✓
  - `06b_ui.xml`: `Calendar` + `44kHz` + `content-desc`=미선택 (added, deselected), dialog closed ✓
  - `07b_ui.xml`: 선택됨=1, 미선택=0 (selected) ✓ / `07d_ui.xml`: 선택됨=0, 미선택=1 (deselected) ✓
  - `08_ui.xml`: 시작 button container `enabled="false"` (start-guard) ✓
  - `10c_ui.xml`: `Calendar` row persists after force-stop; `추가할 앱이 없습니다` gone ✓
- **Stage 7 (Main UI + per-app settings storage + persistence) is complete and committed.** All five prior Gemini runs (1359/1452/1515/1530/1558) were legitimate; every abort was plan-side (step 4 stale string, step 6 auto-close assumption, step 8 over-broad `정지` check) — all fixed in `docs/testplans/STAGE7-UI-VM.md`, none were app bugs.
- **Commit gates (repeated as "gate (3) real-device" below):** (1) Gemini PASS with evidence ✓, (2) Claude evidence review ✓, (3) real-device USB-DAC items — per RULES.md line 25 ("missing real-device responses do not block progress") this is **non-blocking** and moves to the real-device batch.
- **Not VM-testable, still pending (real device, user):** USB DAC relay (stages 4/5) and BT A2DP (stage 6) — hardware the emulator cannot provide. See the real-device USB-DAC procedure below.
- **Next code stage: 6 — Bluetooth path** (codec query via `getCodecStatus()`, libsoxr resample, Oboe output). Not yet coded. Start in a **new chat** (RULES: one stage per chat).
- Remaining un-coded stages: 6 (BT), 8 (DAC info display), 9 (settings + library swap).

### Real-device USB-DAC test (stages 4/5) — user action, needs hardware

Materials: phone (arm64), OTG adapter, a real USB DAC (UAC1 or UAC2), a music app with a known track.
1. `export JAVA_HOME=/d/code/jdk-25`
2. `cd /d/code/android.apps && gradlew.bat assembleDebug`
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Connect OTG + DAC, allow the USB permission prompt in the app.
5. Open the app → the "USB" output row should show the DAC name (not `USB DAC를 연결하세요 (OTG)`).
6. Add a source app, select it, select USB output, tap 시작.
7. Expected: clean sound from the DAC, no crackle/underrun; `adb logcat -s BitPerfect:*` shows streaming start and no isochronous errors.
8. Report: did sound come out? any crackle? the exact logcat lines around start.

## 2026-10-10 STAGE7-UI-VM: run 1558 review — FAIL at step 6 was a plan bug (assumed picker stays open), app worked correctly; plan fixed, ready for re-run

- Gemini run `2026-10-10_1558` (steps 0–5 all PASS; step 2 REUSE path clean; step 4 cleared with the corrected `USB DAC를 연결하세요 (OTG)` string): **ABORT at step 6** — "✓ node is not found, and the dialog closed immediately (main screen is visible)". `APP_LABEL` = `Calendar`, tap at (540, 384). Deviations: NONE.
- Root cause (verified, not a guess): **plan bug, not an app or executor fault.** The plan expected the picker dialog to *stay open* after tapping an app row, showing a trailing `✓`, followed by a `닫기` tap. The app actually behaves differently by design: `addApp()` in [MainScreen.kt:352](app/src/main/java/com/bitperfect/relay/ui/MainScreen.kt#L352) does `settings.addApp(AppEntry(pkg, label))` **and** `showAppPicker = false` in one action — tapping a row adds the app *and* dismisses the dialog. The `✓` marker only renders when the picker is **reopened** for an already-added app.
- Evidence (`docs/reports/2026-10-10_1558_STAGE7-UI-VM/06b_ui.xml`) proves the app did the right thing after the tap: `Calendar` row present with `44kHz` (default rate), `content-desc` = `미선택` (newly added, deselected), `삭제` icon present, and the dialog is gone (no `앱 추가`, no `닫기`). That is exactly the state the plan expected at its `06d_ui.xml` sub-step — the app just reached it one sub-step earlier. `05b_ui.xml` and `06_ui.xml` (both 22326 B, dialog open) confirm the pre-tap state was correct.
- Minor note: Gemini wrote an auxiliary check script `check5.py` inside the run folder. That is a permitted location (writes are only restricted to the run folder) and is a diagnostic, not a protocol violation.
- Fixes (in `docs/testplans/STAGE7-UI-VM.md`):
  - Step 6 rewritten: expected flow is now "tap → dialog auto-closes → main-screen row appears". Expected in `06b_ui.xml`: dialog closed (no `앱 추가`/`닫기`), `추가할 앱이 없습니다` gone, row `APP_LABEL` with `44kHz` and `content-desc` = `미선택`. Evidence reduced to `06.png`, `06_ui.xml`, `06b_ui.xml` (the `06c`/`06d` `닫기` sub-steps are removed). A NOTE explains the auto-close behavior and that `✓` only appears on reopen.
  - Step 10 first bullet fixed: the "close (닫기)" after adding is removed — the dialog auto-closes on add (same behavior as step 6). Sequence is now: open picker → dump `10_ui.xml` → tap `APP_LABEL` → `sleep 2` → S → `10.png` → dump `10b_ui.xml` (after the tap).
- All five Gemini runs (1359, 1452, 1515, 1530, 1558) are legitimate and faithful — evidence present, Deviations: NONE, clean ABORTs. No Gemini misbehavior to flag.
- Action for user: hand Gemini the one-line instruction: "docs/testplans/STAGE7-UI-VM.md 읽어" (re-run; the x86_64 APK is already built at the standard path, and the VM will likely be reused).
- Not committed: unchanged — stage-7 commit still gated on (1) Gemini PASS with evidence, (2) Claude evidence review, (3) real-device USB-DAC items.

## 2026-10-10 STAGE7-UI-VM: run 1530 review — FAIL at step 4 was a stale plan string, not an app bug; plan fixed, ready for re-run

- Gemini run `2026-10-10_1530` (steps 0–3 all PASS; step 2 REUSE path — `HypervisorPresent`=True, `sys.boot_completed`=1, guest `cpuinfo` shows i5-13600KF not QEMU; step 3 install Success): **FAIL at step 4** — expected string missing.
- Root cause (verified, not a guess): **plan bug, not an app or executor fault.** Step 4 expected `USB DAC가 연결되어 있지 있습니다`, but the app's empty-USB row actually renders `USB DAC를 연결하세요 (OTG)`. Verified in code: [MainScreen.kt:492](app/src/main/java/com/bitperfect/relay/ui/MainScreen.kt#L492) renders `R.string.ui_usb_connect_first` ("USB DAC를 연결하세요 (OTG)"). The string `ui_usb_empty` ("USB DAC가 연결되어 있지 있습니다") is a dead, unreferenced resource. `docs/ref/ui-spec.md` is silent on the exact empty-state text, so the app's string is valid and the fix belongs on the plan side.
- Fix #1 (in `docs/testplans/STAGE7-UI-VM.md`, step 4): expected string changed to `USB DAC를 연결하세요 (OTG)` — the string the app actually renders.
- Fix #2 (in `docs/testplans/STAGE7-UI-VM.md`, step 8 + 8b): the original check "no node with text `정지`" was a latent false-FAIL — the legacy "4~5단계 시험 (WAV → DAC)" card has a text-only `정지` button that is always visible. Scope narrowed to "no node whose `content-desc` is `정지`", which only the Stage-7 stop button carries, and it is only rendered while playing. Added an explanatory note so Gemini does not treat the legacy card's `정지` as a failure.
- Verified clean: grep of the plan shows all `정지` references now scoped to `content-desc`; no other stale `USB DAC가 연결` expectation remains.
- All four Gemini runs (1359, 1452, 1515, 1530) are legitimate and faithful — evidence present, Deviations: NONE, clean ABORTs. No Gemini misbehavior to flag.
- Action for user: hand Gemini the one-line instruction: "docs/testplans/STAGE7-UI-VM.md 읽어" (re-run; the x86_64 APK is already built at the standard path).
- Not committed: unchanged — stage-7 commit still gated on (1) Gemini PASS with evidence, (2) Claude evidence review, (3) real-device USB-DAC items.

## 2026-10-10 STAGE7-UI-VM: run 1515 failed at step 4 (UnsatisfiedLinkError) — root cause fixed, ready for re-run

- Gemini run `2026-10-10_1515` (steps 0–3 all PASS, step 2 REUSE path — the step-2 rewrite worked as intended): **FAIL at step 4** — app crashed immediately on launch: `UnsatisfiedLinkError: dlopen failed: library "libengine.so" not found`.
- Root cause (verified, not a guess): `app/build.gradle.kts` had `abiFilters += "arm64-v8a"` only. The APK contained `lib/arm64-v8a/libengine.so` and nothing for x86_64, but the test AVD `bp_api36` is an **x86_64 emulator** → JNI load fails at launch.
- Fix: `abiFilters += setOf("arm64-v8a", "x86_64")` ([AI judgment] per RULES.md autonomous-decision "supported ABIs"; rationale: RULES.md says non-real-device features are validated on the VM first, and the VM is x86_64). SPEC.md ABI line synced.
- Verified: `gradlew.bat assembleDebug` → BUILD SUCCESSFUL; APK now contains both `lib/arm64-v8a/libengine.so` (618848 B) and `lib/x86_64/libengine.so` (603104 B). The C++/libusb code is arch-agnostic (POSIX backend) so the x86_64 build should behave identically.
- Action for user: hand Gemini the one-line instruction: "docs/testplans/STAGE7-UI-VM.md 읽어" (re-run; the fresh APK is already built at the standard path).
- Note: install on the VM still needed (step 3 of the run) — the previous APK on the VM, if any, is the arm64-only one and would crash again.

## 2026-10-10 STAGE7-UI-VM: both Gemini runs diagnosed, step 2 fixed, ready for re-run

- Gemini run outcomes (reports under `docs/reports/`):
  - `2026-10-10_1359` — ABORT at 2a: `VirtualizationFirmwareEnabled` = `False`. Host-side blocker only; user has since enabled virtualization — `HypervisorPresent` now reports `True` (the plan's 2a check uses it; the WMI `False` is a known false-negative on this build).
  - `2026-10-10_1452` — ABORT at 2d, **plan bug, not an app or executor fault**: the prereq check let Gemini reuse the already-running `emulator-5554` (skipping 2b/2c), but 2d demanded `02_emu.log`, which only exists if 2b ran. In the reuse branch 2d could never pass. Gemini behaved correctly (Deviations: NONE, clean ABORT).
- Fix (in `docs/testplans/STAGE7-UI-VM.md`, step 2 rewritten):
  - Step 2 now branches after 2a on `$ADB devices`: **LAUNCH path** (2b → 2c → boot poll → 2d-L, `02_emu.log` grep as before) vs **REUSE path** (2b/2c/poll skipped → 2d-R).
  - 2d-R evidence source: guest CPU identity. `$ADB -s $DEVICE shell cat /proc/cpuinfo > $RUN/02_virt.txt`; PASS = `model name` line present and **not** containing `QEMU`. Verified live on this box: the running WHPX guest reports `model name : 13th Gen Intel(R) Core(TM) i5-13600KF` with zero `qemu` markers in `cpuinfo`; a TCG fallback would report `QEMU Virtual CPU version …`. (`ro.boot.virt` is empty on Windows/WHPX — still unusable.) 2d-R also requires `sys.boot_completed` = `1` before the cpuinfo check.
  - All ABORT rules, evidence file names, and steps 3–11 unchanged.
- Environment now (verified): `emulator-5554` running and booted (`sys.boot_completed` = 1), `HypervisorPresent` = `True`, `com.bitperfect.relay` not yet installed on the VM, build APK present from run 1452. The re-run will take the REUSE path and should clear step 2 immediately.
- Action for user: hand Gemini the one-line instruction: "docs/testplans/STAGE7-UI-VM.md 읽어".
- Not committed: unchanged — stage-7 commit still gated on (1) Gemini PASS with evidence, (2) Claude evidence review, (3) real-device USB-DAC items.

## 2026-10-10 STAGE7-UI-VM plan updated: GPU-accelerated boot (user: "VM GPU 허용까지 문서에")

- `docs/testplans/STAGE7-UI-VM.md` rewritten in place — single self-contained doc Gemini runs from "읽어: docs/testplans/STAGE7-UI-VM.md" alone (protocol referenced by the plan itself; no other file needed).
- Step 2 "boot the VM" now enforces the GPU path, with fast-fail ordering:
  - 2a — host prerequisite: `powershell (Get-CimInstance Win32_Processor).VirtualizationFirmwareEnabled` → `02_vtx.txt` must say `True`; `False` → immediate ABORT with owner note (VT-x must be enabled in BIOS by the owner). **Currently `False` on this PC (i5-13600KF) — the run will ABORT at 2a until the user enables VT-x + reboots.**
  - 2b — launch: `$EMU -avd bp_api36 -no-window -no-audio -gpu swiftshader_indirect -memory 4096` (replaces the broken `-no-accel -gpu off` path; SwiftShader-indirect = GPU pipeline inside the guest, robust once KVM exists; `-gpu host` still ruled out — zero usable VRAM path per 2026-10-10 entry).
  - 2c — 60s liveness check (`Get-Process emulator` → `02_proc.txt`): catches the swiftshader-without-KVM segfault (exit 139) in ~60s instead of burning the 600s boot poll.
  - poll — `sys.boot_completed` = `1` within 600s (unchanged).
  - 2d — acceleration evidence: `getprop ro.boot.virt` → `02_virt.txt` must contain `kvm`; boot without KVM = ABORT (not the GPU-accelerated path).
- All other steps (3–11, evidence names, tap.py, ABORT rules) unchanged.
- Action for user: enable CPU virtualization (VT-x) in BIOS + reboot, then hand Gemini the one-line instruction: "docs/testplans/STAGE7-UI-VM.md 읽어".
- Not committed: unchanged — stage-7 commit still gated on Gemini's evidence review.

## 2026-10-10 Test-process change: Gemini 3.1 Pro executes tests, VM-first validation

- Current stage: Stage 7 remains code-complete / build-clean / **not committed** — commit is now gated on a Gemini VM test run + real-device USB-DAC items.
- Process change (user directive, now codified in RULES.md "Test process"):
  - Testing moves from Claude to **Gemini 3.1 Pro (Antigravity)**. Claude = management/design/coding/review/plan authorship/VM setup; Gemini = executes approved plans verbatim + writes the report, nothing else.
  - All non-real-device-mandatory features are validated on the **Android VM (emulator)** first.
- Docs created (this entry):
  - `docs/TEST-PROTOCOL.md` (new, binding) — Gemini allowlist (build, emulator/adb control, writes only in the run folder, report), 10 prohibitions (no file edits outside run folder, no git, no step reorder/skip, no retry beyond plan, no PASS without an evidence file, no paraphrased output), 4-item per-step checklist, evidence rules (full logs no pipes, named PNG/XML), run-folder contract (`docs/reports/<YYYY-MM-DD_HHMM>_<plan-id>/`, `00_meta.txt` first / `99_meta.txt` last), mandatory `REPORT.md` structure with a `Deviations` section.
  - `docs/testplans/STAGE7-UI-VM.md` (new) — 12-step VM plan: build → boot → install → initial render (13 exact strings) → picker → add app → selection toggle → start-button guard (`enabled="false"`, no relay possible on VM) → delete flow (incl. the `삭제` text-collision note) → persistence across `am force-stop` → report. Every step: exact commands, expected output, named evidence files, ABORT-on-fail. Includes `tap.py` helper (content-desc/text → bounds center; `NODE NOT FOUND` = FAIL, no coordinate guessing).
  - `RULES.md` — new "Test process (role split)" section; evidence review is Claude's, never the executor's summary.
- VM state (Claude-owned, **paused by user instruction 2026-10-10**): AVD `bp_api36` (android-36, google_apis, x86_64) created. Boot not yet successful:
  - `-gpu swiftshader_indirect` → segfault (exit 139) after gfxstream init, with "x86_64 emulation may not work without hardware acceleration!" (VT-x off in BIOS, `-no-accel` mandatory, zero usable VRAM rules out `-gpu host` despite RTX 4090).
  - `-gpu off` (full log to file, no pipes) → emulator process stays alive (no crash), `emulator-5554` appears in `adb devices` but remains **offline** for >10 min; `sys.boot_completed` never reached. Likely software-emulation slowness (no VT-x) on android-36. Emulator stopped per user instruction.
  - Next levers when resumed: enable VT-x in BIOS (removes the root cause), else try `-gpu guest`, `-no-snapshot -snapstorage`, an older API level (e.g. android-33) AVD which boots faster in software.
- Not committed: nothing new. Stage-7 gate list now: (1) VM boot fix (Claude, paused), (2) Gemini runs STAGE7-UI-VM and produces evidence, (3) Claude reviews evidence files, (4) real-device USB-DAC items, (5) commit. User may defer the VM leg and go straight to real-device testing — decision pending.

## 2026-10-10 Stage 7 (UI + persistence) — spec + code complete, build clean; device test pending, not committed

- Current stage: Stage 7 — new main-screen UI per user spec + SharedPreferences persistence. Stage 8/9 still pending. **Compile + assemble now clean; no device testing yet; not committed (stage = code+build+doc, device test is the user's remaining gate).**
- Spec (done this session, docs only):
  - `docs/ref/ui-spec.md` v2 — full Stage-7 behavior spec (app list add/persist, per-app sample-rate dropdown, single 3-state selection, output row + removable selection, USB/Bluetooth tree, start/stop/switch rules, permission-denied handling, background FGS).
  - `docs/ref/ui-draft.md` v2 — matching UI draft; `ui_draft_v1.png` + `screenshot_ui_check.png` added under `docs/ref/`.
- Done (code):
  - `app/src/main/java/com/bitperfect/relay/data/AppSettings.kt` (new) — SharedPreferences-backed store: `AppEntry(packageName, displayName, sampleRate)`, `OutputSelection(type, id, name)`; StateFlows `apps` / `selectedApp` / `selectedOutput`; mutators `addApp` / `removeApp` (clears selection if removed) / `setAppRate` / `setSelectedApp` / `setSelectedOutput`; JSON helpers via `org.json`. **Read-verified clean (no compile issues).**
  - `app/src/main/java/com/bitperfect/relay/ui/MainScreen.kt` (rewritten) — Stage-7 UI: app picker dialog with `PackageManager` installed-app list + `getApplicationIcon`; app list rows (icon, name, rate dropdown, delete-confirm dialog, 3-state selection); output row (current device or 없음, tap = remove); expandable USB / Bluetooth tree (USB leaf: name + UAC 1.0/2.0 badge + power icon, display-only; BT leaf: name + refresh + codec dropdown, display-only, Stage 6 not implemented); start/stop button + status line; USB permission intent + broadcast receiver; `usbPermissionIntent()` helper.
  - `app/src/main/java/com/bitperfect/relay/service/RelayService.kt` — Stage-7 service changes: USB-detach receiver in `onCreate` (auto stop + status "USB DAC 분리됨 → 자동 정지"), `switchOutput` (re-route only, no stop) via `ACTION_SWITCH_OUTPUT` with `EXTRA_OUTPUT_ID` / `EXTRA_SAMPLE_RATE`, `openUsbById` (match by vendorId/productId/serial), `usbOn` flag.
  - `app/src/main/res/values/strings.xml` — all Stage-7 Korean strings added (ui_app_*, ui_output*, ui_usb*, ui_bt*, ui_start/stop, ui_status_*). Read-verified.
- DataStore → SharedPreferences: the offline Gradle cache lacks a usable `material-icons` artifact AND has a broken DataStore artifact → all `Icons.Filled.*` replaced with text glyphs (`▲▼ ⟳ ✓ 🗑 ⚡ 🔋`) inside `Text()`, and persistence uses SharedPreferences. `libs.androidx.datastore.preferences` is now unused at `app/build.gradle.kts:66` — **keep until the offline cache is checked** (removing it may break resolution if the artifact is present but broken).
- Build status: **CLEAN** — all compile errors fixed and both targets pass:
  - `:app:compileDebugKotlin` → success, no errors.
  - `:app:assembleDebug` → BUILD SUCCESSFUL (APK: `app/build/outputs/apk/debug/app-debug.apk`).
  - Fixes applied (iterating against the compiler, which is the source of truth against the broken offline cache):
    - All 8 `Icons.Filled.*` → text glyphs in `Text()` (▲▼ ⟳ ✓ 🗑 ⚡ 🔋); icon imports dropped.
    - Wrong background import → `androidx.compose.foundation.background`; added `import com.bitperfect.relay.R`.
    - Removed `@Composable` from `usbPermissionIntent()` (called from `onReceive`); moved the USB-permission receiver block after `onStop()` (local-fun forward reference).
    - `AppIcon` Drawable → rasterized via `Bitmap.createBitmap` + `Canvas` + `drawable.draw(canvas)`, then `Image(bitmap = bmp.asImageBitmap(), …)`.
    - USB identity: `d.deviceSerial` → `runCatching { d.serialNumber }.getOrNull() ?: ""` (nullable + SecurityException-safe); `cfg.attributes` → `cfg.isSelfPowered`; `RelayService` match → `(it.serialNumber ?: "")`.
    - `RelayService.kt:112` — `UsbManager.EXTRA_DEVICE_NAME` missing from the compileSdk-37 stub jar → AOSP-verified literal `"deviceName"` (comment in code).
    - All named-then-positional `Modifier` args → labeled `modifier =` (buttons, `OutlinedTextField`).
    - **API assumptions disproven by the compiler this pass** (do NOT reuse these): `OutlinedButtonDefaults` does not exist in this material3; `OutlinedButton` in this version has **no `contentPadding` parameter** → both dropdown buttons use plain `OutlinedButton(onClick, modifier = Modifier.height(40.dp))` with the default padding. `Modifier.padding(start = X, vertical = Y)` is invalid (args from two different overloads) → split into chained `.padding(start = …).padding(vertical = …)`. Compose `Text()` has no `contentDescription` parameter → accessibility label via `Modifier.semantics { contentDescription = … }`.
- Verified (this session): AppSettings.kt read-clean; `engine.cpp` read-clean (all JNI bindings intact); strings.xml complete; **`compileDebugKotlin` + `assembleDebug` both pass**.
- Not verified: **no device testing at all yet** (device R5CY90H76VA ready; install + spec-driven UI test is the remaining gate).
- Next steps (awaiting user go-ahead — NOT started):
  1. `adb install -r` → device test per spec: add app / persist across restart, rate dropdown, 3-state selection, output row + USB tree with DAC (OTG), start (permission flow), stop on app/output change, output switch = re-route only, USB DAC unplug/replug behavior, permission-denied keeps app + drops output.
  2. Update this entry with results; commit (English message, stage number, `Co-Authored-By: Claude Code <noreply@anthropic.com>`) **only on stage completion**.
- Uncommitted files at time of writing: `AndroidManifest.xml`, `engine.cpp`, `usbplay.cpp/.h`, `Engine.kt`, `RelayService.kt`, `MainScreen.kt`, `strings.xml`, `data/AppSettings.kt` (new), `docs/PROGRESS.md`, `docs/ref/ui-spec.md`, `docs/ref/ui-draft.md` (new), `run_build.bat`.

## 2026-10-10 Real-device capture test (autonomous, OCR-driven) — AudioRecord fix verified to build; test in progress

- Current stage: Stage 2 capture test on the real device, driven autonomously (adb + screenshot + local Ollama OCR, no human in the loop). Target app: **Tidal** (`com.aspiro.tidal`, per user directive — YouTube replaced by Tidal). USB-DAC half not testable (no DAC connected; UI shows "USB 오디오 장치가 없습니다").
- Bug found & fixed this session (real-device logcat, 2026-10-09): `AudioRecord.Builder` + `AudioPlaybackCaptureConfiguration` threw `IllegalStateException: Cannot both set audio source and set playback capture config`. AOSP `checkAndThrow()` forbids `setAudioSource()` when a playback capture config is set.
  - Fix: `RelayService.kt` — drop `setAudioSource(...)`; build `AudioFormat` (PCM_FLOAT, sampleRate, STEREO) and pass via `AudioRecord.Builder().setAudioFormat(...).setBufferSizeInBytes(...).setAudioPlaybackCaptureConfig(...).build()`.
  - Status: built + installed; capture-start log now passes the AudioRecord construction (logcat shows `대상 앱: com.aspiro.tidal uid=10355 샘플레이트=44100 Hz`), full capture still being verified.
- Also fixed this session: `AndroidManifest.xml` — added `<queries>` entry for the target app. Without it `getApplicationInfo()` threw `NameNotFoundException` (package visibility, Android 11+). Verified working on device.
- Consent flow notes (Android 16, one-app share mode): page 1 = bottom-sheet dialog with 취소/다음 (both at y≈1673 px on 1080x2340); page 2 = app picker, selecting the Tidal card confirms (no separate confirm button). Tapping the right-side bottom button in two rounds closed the dialog → next round verifies the button order via OCR before tapping.
- Test method (for future sessions): `adb -s R5CY90H76VA exec-out screencap -p > screen.png` (plain `screencap -p /sdcard/...` fails on this device), then local Ollama `qwen3.8:27b` chat API at `localhost:11434` for OCR (warmup `ask("OK")` first). `uiautomator dump` returns stale trees — do not use for verification.
- Result: pending (WAV pull + RMS/max analysis, then this entry gets the final verdict).
- Uncommitted at time of writing: `AndroidManifest.xml` `<queries>` + `RelayService.kt` AudioRecord fix (awaiting user commit instruction).

## 2026-10-09 Fixed invisible button text — dropped Material You dynamic colors (fixed brand scheme)

- Current stage: Stage 5 code done. Real device: capture crash fix confirmed working (user ran capture with Apple Music, "recording in progress" — no SecurityException). This session: fixed the reported invisible button text.
- Symptom: button labels invisible on the real device (S21 / Android 16, One UI).
- Cause: `BitPerfectTheme` (Theme.kt) used `dynamicLight/DarkColorScheme` on API 31+ — the device (API 36) always takes that path. Samsung One UI's dynamic palette can yield an `onPrimary` with near-zero contrast against `primary`; filled `Button` text uses `onPrimary` → invisible. The fixed fallback schemes (Accent/DarkBlue) are high-contrast, isolating the dynamic branch as the only suspect path on this device.
- Done (files):
  - `app/src/main/java/com/bitperfect/relay/ui/theme/Theme.kt`: removed the dynamic-color branch + unused imports (`Build`, `dynamic*ColorScheme`, `toArgb`, `LocalContext`); always use the fixed `DarkColorScheme`/`LightColorScheme`; added explicit `onPrimary = DarkBlue`, `onSecondary = DarkBlue` to both schemes. Filled button = Accent (#4FC3F7) background + DarkBlue (#0D1B2A) text → high contrast on every device. [AI judgment: fixed brand scheme over Material You — deterministic contrast + consistent identity for a music app.]
- Remaining (next session — if the terminal tool is enabled, the bot runs these directly):
  - Build: `.\gradlew.bat assembleDebug` (from d:\code\android.apps)
  - Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
  - Screenshot: `adb shell screencap -p /sdcard/bp_shot.png` → `adb pull /sdcard/bp_shot.png d:\code\android.apps\screenshot.png` → verify button text visibility
  - Continue capture test: stop → WAV pull → bit-compare; then USB combined test (DAC solo).
- Unverified / pending questions:
  - Button text visibility after reinstall (screenshot → `d:\code\android.apps\screenshot.png`, bot reads it).
  - Build success (pending user run).
- Real-device test results:
  - Capture start no longer crashes (Apple Music + "recording in progress" — SecurityException fix verified on device).
  - Button text invisible (user report, before fix).

## 2026-10-09 Fixed capture crash: SecurityException — FGS `mediaProjection` type required (Android 14+)

- Current stage: Stage 5 code complete; combined real-device test (capture + USB output) still pending. This session: diagnosed the capture crash on the real device, fixed it, rebuilt, reinstalled.
- Error (logcat 17:01–17:04, 7+ crashes when tapping "캡처 시작"):
  `SecurityException: Media projections require a foreground service of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` at `MediaProjectionManager.getMediaProjection` (RelayService.kt:127).
- Cause: Android 14+ (device runs Android 16, targetSdk 36) requires the service to already be a foreground service of type `mediaProjection` before `getMediaProjection()` succeeds. Old code called `getMediaProjection()` first (line 127) and only later a **no-type** `startForeground(id, notif)` (line 205) → wrong timing and missing type. The earlier audit fixed the WAV path and missed this.
- Changed (code):
  - `RelayService.kt`: added `import android.content.pm.ServiceInfo`; call `startForeground(NOTIF_ID, notif, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` at the top of `startCaptureTest`, **before** `getMediaProjection()`; the later `startForeground` (step 7) now carries the same types.
  - Manifest already declares `FOREGROUND_SERVICE_MEDIA_PROJECTION` + `foregroundServiceType="mediaProjection|connectedDevice"` → unchanged.
- Verified:
  - `gradlew.bat assembleDebug` → BUILD SUCCESSFUL (only pre-existing deprecation warnings).
  - `adb install -r` → Success (device R5CY90H76VA).
- Other observations (PC connected):
  - `/dev/bus/usb/001,002` empty, `host_connected=false` → UI "USB 오디오 장치가 없습니다" is **expected** (DAC physically unplugged; single-port limit). Not an app bug.
  - Stale USB permission grants in `dumpsys usb` belong to the uninstalled old uid (10393); the new app (uid 10432) will re-show the USB permission dialog on first DAC connect.
- Unverified (real device, user solo test — same steps as the entry below):
  - "Start capture" end-to-end after the fix (no crash → WAV written → USB streaming).
  - DAC VID/PID, openDevice/claimInterface, bit-perfectness comparison, USB continuity.

## 2026-10-09 Stage 2+4+5 combined real-device test — pre-flight audit (PC side) done, blocker fixed, APK reinstalled

- Current stage: Stage 5 code complete; combined on-device test (capture + USB output) still pending. This session: verified everything checkable from the PC side, fixed one blocker, reinstalled the APK.
- User constraint: S21 has one USB port → PC and DAC cannot be connected at the same time. PC connected = bot checks; PC off + DAC on = user solo sound test.
- Findings (via adb while PC connected):
  - Device: SM-S731N (Galaxy S21 Exynos, Android 16, targetSdk 36).
  - OTG: `settings get global otg_enabled` = 1 (persisted).
  - **Correction**: the "USB device 002" from the previous session is **not** the DAC — `/dev/bus/usb/` lists two **empty host-controller buses** (001, 002); no downstream device attached. DAC identity (VID/PID) still unverified — needs the solo connection.
  - Runtime permissions: RECORD_AUDIO + POST_NOTIFICATIONS ungranted (app requests both at "Start capture"), BLUETOOTH_CONNECT ungranted (unused for now).
  - **Blocker found & fixed**: capture WAV was written to shared `Music/` via `FileOutputStream` → fails on Android 13+ without `MANAGE_EXTERNAL_STORAGE` (not in manifest) → capture would die right after start. [AI judgment: use the app-private external dir + fixed filename — no permission needed, still adb-pullable, and matches the UI wording.]
- Changed (code):
  - `RelayService.kt`: output → `getExternalFilesDir(DIRECTORY_MUSIC)/bitperfect/capture_test.wav`; dropped the now-unused `SimpleDateFormat/Date/Locale` imports.
  - `MainScreen.kt`: result-path hint + USB play-test default path → the new path.
- Verified:
  - `gradlew.bat assembleDebug` → BUILD SUCCESSFUL (only pre-existing deprecation warnings).
  - Install: old device package had a **different signing key** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) → uninstalled, fresh install OK. All app permissions reset by the fresh install.
  - On device: package present, OTG=1, app external dir accessible.
- **New pull path** (supersedes the old `/storage/emulated/0/Music/bitperfect/` in the 10-07 entry):
  - `adb pull /storage/emulated/0/Android/data/com.bitperfect.relay/files/Music/bitperfect/capture_test.wav`
- User solo test steps (PC disconnected):
  1. Unplug PC, plug the FIIO DAC into the phone USB-C (OTG already on).
  2. Relay app → USB test section → DAC appears in list → "연결" → USB permission dialog → expect "연결됨 · ..." with UAC2 info.
  3. Target app (Apple Music) → play a 44.1k/48k track.
  4. Relay app → "캡처 시작" → grant mic + notifications + MediaProjection consent → ~10 s → "캡처 정지".
  5. Reconnect PC → pull the WAV (path above) + `adb logcat` grep `BitPerfect` (`usbStreamBegin` / `usbStreamOk` / errors).
- Remaining / unverified:
  - DAC identity (VID/PID) — only when actually plugged in.
  - USB permission + `openDevice` + `claimInterface` on the real DAC (stage-4 core).
  - Capture bit-perfectness vs original; USB output continuity; UAC2 feedback behavior.
  - Port mode switch PC→DAC after unplug (settings persisted; real-hardware switch unverified).

## 2026-10-09 Language rule change + all MD docs translated to English

- User instruction: chat (reports) stays Korean; all MD files and code comments in English. Existing code stays as is; only newly written code in English.
- Done:
  - Translated to English: `AGENTS.md`, `RULES.md`, `docs/SPEC.md`, `docs/PROGRESS.md` (this file), `docs/audio_usb_hal_analysis.md`, `docs/ref/libs.md`, `docs/ref/ui-spec.md`, `docs/ref/usb-dac.md`.
  - `RULES.md` new "Language" section: chat Korean / MD + new code (incl. comments) English / existing code unchanged / app UI strings stay Korean in `strings.xml`. Commit message rule changed to English.
  - Not touched: existing code (Korean comments remain), `CLAUDE.md` (only `@AGENTS.md`), `.vendor_tmp/` (vendored upstream files).
- Verified: files re-read after rewrite. Build not applicable (docs only).
- Not verified: nothing pending.

## 2026-10-09 Stage 5 (capture → USB streaming, foreground service) code complete

### Current stage
- Stage 5 code complete, build succeeded. Real-device testing pending (TBE).
- Combined real-device tests for stages 2/4/5 not yet done.

### Done
- Stage 5 implementation (capture float → USB DAC streaming):
  - `Engine.kt`: added `nativeUsbStreamBegin/Feed/Stop/Ok/LastError` JNI bindings + Kotlin wrappers (`usbStreamBegin/Feed/Stop/ok/lastError`).
  - `engine.cpp`: implemented the 5 JNI functions (delegating to the usbplay streaming API).
  - `usbplay.cpp`: added stage-5 streaming — `pump_stream` (feed queue `g_feed` → isochronous ring-buffer transfer, silence for shortfall, feedback speed control) · `usbplay_stream_begin/feed/stop/ok`. Feed queue capped at **4s**; oldest dropped on overflow (real-time priority). Sign extension only (32bit float → subslot bits), no resampling.
  - `RelayService.kt`: in the capture loop `Engine.usbStreamBegin(sampleRate)` → `Engine.usbStreamFeed(samples, frames)` per `read` → `usbStreamStop` on `onError`/`onStopped`. Target-package targeting (`addMatchingUid`) and MediaProjection `onStop` handling already exist from stage 2.
  - `CMakeLists.txt`: added `usbplay.cpp`.
- Fixed compile error: in `usbplay.cpp` the `namespace {}` boundary incorrectly cut between the pump and the public API, leaving `pump_stream`·`usbplay_*` outside the namespace — brace mismatch; fixed by consolidating the namespace over the whole file (first compile since stage 4; unverified code had been left behind).
- Cleanup:
  - Deleted `app/src/main/cpp/engine.cpp` (dead duplicate JNI, superseded by `app/src/main/cpp/engine/engine.cpp`).
  - Added research temp files (`/vendor_tmp/`, `aosp_*`, `tmp_search.ps1`, `build_stage4.log`) to `.gitignore`.
  - Confirmed `nativeFdValue` already removed (0 occurrences).
- Verified:
  - `gradlew.bat :app:assembleDebug` **BUILD SUCCESSFUL** (NDK r28, arm64-v8a).
  - `llvm-nm` check: all 15 JNI symbols (`Java_com_bitperfect_relay_Engine_native*`) + 12 `usbplay_*` symbols exported from `libengine.so`.
- Remaining limitation (kept): original-sound leakage (non-goal) — stock API limit, decision unchanged. UI/notification must state it honestly (stage 5 UI text is still the stage-2 "WAV saving" wording — update after real-device test).

### Remaining
- **Real-device combined test** (stages 2/4/5): play target app → start capture → USB DAC output → stop. Checklist:
  1. 44.1k/48k track: verify `usbStreamBegin` matches a supported rate
  2. Whether DAC output starts together with capture (1–2s drag tolerated — 4s cap)
  3. Check errors via `usbStreamOk`/`LastError` (UAC2 feedback working?)
  4. Leakage (original output) perception
  5. On stop: WAV saved + `usbStreamStop` clean shutdown
- Stage-5 dedicated UI in `MainScreen.kt` (USB connect → capture → streaming flow) — after real-device test.
- Foreground notification: show "capturing / USB output" state + stop button (currently the default notification).

### Unverified
- `pump_stream` branch when `g_info.subslot` is 0 (descriptor parse failure) — safe because `usbplay_open` validates `subslot>0`.
- UAC1 sync + `isAsync` ⇒ `hasFb=false` — normal (no feedback).
- Whether `usbplay_feed`'s `frame_count` is always 1 frame (stereo = 2 samples) — `RelayService` passes the frame count from `read` as-is, so it's fine.

## 2026-10-09 Decision: capture method adopted (all apps) + "bit-perfect" = unprocessed relay (symbolic) + reject-leakage principle kept

### Current stage
- Preparing stage 5 (capture + USB output connection, foreground service). Doc direction finalized · committed.
- Stage 2/4 real-device tests TBE (user no response; not blocking).

### Done
- User's final decision (direct instruction + core-rule question answers):
  1. Capture is this app's **method**. **All** target apps, including Apple Music, use capture.
  2. "Bit-perfect" is a **symbolic name** — not "true bit-perfect". The goal is to keep the **relay path (after capture) unprocessed** (no resample · volume · dither · mixing · noise).
  3. **Source-based (UAPP) redesign (2nd revision) discarded** — back to the original capture path.
  4. Core principle "reject original-sound leakage" is **kept** (answered "keep reject-leakage too" to the question). Capture's inherent original output (also playing on the phone's default output) is a **non-goal** due to stock API limits (track volume 0 ⇒ capture 0); do not frame it as acceptable.
- UAPP analysis (top priority, subagent, public-info based) complete:
  - UAPP "true bit-perfect" = source-based direct decode + custom kernel USB isochronous driver (not Reva).
  - UAPP "Apple Music" = MediaProjection capture best-effort (not true bit-perfect).
  - For reference only; not our adopted goal (capture method adopted, reject-leakage principle kept).
- Doc direction finalized: updated `SPEC.md` (goals · correction note · core behavior rules · stage 3) and `RULES.md` (core rules) to "capture adopted + unprocessed relay + reject-leakage kept".
- Commit: the 3 docs above (SPEC · RULES · PROGRESS). Stage 4 code separate.

### Remaining
- **Stage 5**: `RelayService.kt` passes captured floats into the engine ring buffer and connects with the stage-4 USB output path. Include foreground notification (stop button) and one-time MediaProjection token handling. The leakage limit must be stated **honestly** in UI/notification (not acceptable; non-goal).
- Batch-integrate stage 2/4 real-device tests (user no response; not blocking).
- Clean up dead JNI (`nativeFdValue`) in `usbplay.cpp` — folded into stage 5.

### Unverified / pending questions
- Stage 2/4 real-device test results (TBE, not blocking).
- Whether the user wants alternatives to reduce leakage (minimize target app volume, output routing) — check after stage 5.

### Real-device test results
- (TBE — user no response)

## 2026-10-09 Decision finalized + UAPP analysis started (top priority) + docs committed

### Current stage
- Before the source-based redesign: investigating the UAPP "Apple Music bit-perfect" approach (top priority, subagent in progress).
- Stage 2/4 real-device tests TBE (user no response; not blocking).

### Done
- User decisions finalized:
  1. Top priority = UAPP (method) analysis → subagent investigation started, awaiting results.
  2. Real-device tests = TBE.
  3. Package name `com.bitperfect.relay` **kept (final)**.
  4. GitHub not approved. Library swap feature **kept**; GitHub integration is **ready but inactive** (enabled when the repo is provided). Currently local load.
  5. Name finalized → docs committed.
- Docs updated to match: SPEC package name "(temporary)" removed, stage 9 "enabled when the GitHub repo is provided" made explicit, libs.md "Current state" section added.
- Commit: doc compaction (AGENTS · RULES · SPEC · PROGRESS · docs/ref) + decision reflected. Stage 4 code separate.

### Remaining
- Once the UAPP investigation results arrive → rewrite SPEC architecture · development stages (whether to switch to source-based).
  - Until the investigation concludes, do not add code that assumes "leakage acceptable" (e.g. stage-5 service guidance text).
- After the UAPP investigation: resume stage progress.

### Unverified / pending questions
- What UAPP actually does on Android (source-based vs capture, Apple Music DRM coverage) — under investigation.
- Stage 2/4 real-device test results (TBE, not blocking).

### Real-device test results
- (TBE — user no response)

## 2026-10-09 Work environment: context 100k → 80k (rollback)

- User instruction: "too slow" → rolled back 100k to 80k (speed first).
- Done:
  - `.claude/settings.json` — all 5 model variables `27b-100k` → **`27b-80k`**, `CLAUDE_CODE_MAX_CONTEXT_TOKENS` 102400 → **81920**.
  - `RULES.md` section 8 "(100k)" → restored to "(80k)" (num_ctx 81920, work-size basis 80k).
  - User env var `OLLAMA_CONTEXT_LENGTH` 102400 → **81920** (machine level 49152 unchanged).
  - Kept the 100k tag (`qwen3.8-coding:27b-100k`) — can be re-upgraded if needed.
- Confirmed: `ollama list` shows the `qwen3.8-coding:27b-80k` tag exists (80k settings intact).
- Next: applies from a new conversation (session) with the 80k model. Existing sessions need a restart.

## 2026-10-09 Stage 3 decision revision (leakage acceptance discarded) + replan

- User 2nd instruction: "This is a music-listening app. Leaking sound is an absolute rejection condition as outside noise. **Reject every factor that interferes with music listening.**" → **discarded both A (tolerate USB-speaker leakage) and D (BT also to speaker) of the initial stage-3 decision (A+D)**.
- Technical basis (existing AOSP investigation, no re-check needed): capture (tee) is tied to track volume — volume 0 means capture 0 (`AudioMixer.cpp postProcess()`). If volume 0 can't be used, the original is audible somewhere → **no leakage-free capture path exists via stock APIs**.
- Rule updates done:
  - `RULES.md` section 0: added design principle (reject anything that interferes with music listening). Section 4: A+D decision → revised (discarded, switch to source-based design).
  - `docs/SPEC.md`: "replanning" block at doc head, core behavior rules core rule, stage-3 item revised.
- Next (in progress): replanning investigation — feasibility of a "kill the original" source-based design (UAPP's Apple Music bit-perfect method, Apple Music Android DRM/transport, stock alternatives) → rewrite SPEC architecture · development stages per the outcome. Until the investigation concludes, do not add code assuming "leakage acceptable" (e.g. stage-5 service guidance text).

## 2026-10-09 Work environment: context 80k → 100k

- Purpose: raise the local model context 80k (81920) → 100k (102400), verify RAM headroom.
- Done:
  - Created new model tag `qwen3.8-coding:27b-100k` (FROM `qwen3.8-coding:27b-80k` + `PARAMETER num_ctx 102400`). 80k·64k tags kept (rollback possible).
  - `.claude/settings.json` — all 5 model variables to `27b-100k`, `CLAUDE_CODE_MAX_CONTEXT_TOKENS` 81920 → **102400**.
  - `RULES.md` section 8 "Context management (80k)" → updated to "(100k)".
  - Set user env var `OLLAMA_CONTEXT_LENGTH=102400` (user level overrides machine level 49152. Reflected on ollama restart — no effect on the current session since the model num_ctx parameter already wins).
- Confirmed:
  - Verified actual load via `/api/ps`: `qwen3.8-coding:27b-100k` **context_length 102400**, size_vram 16.4GB (Q4_K_M 27B).
  - RAM: 64GB total, ~40GB free after load. KV cache (q8_0) scales with context — 80k→100k is ~1.25x (≈3GB level), within available range.
  - Model structure note: qwen35 hybrid (only 1/4 of 65 blocks are full attention, head_count_kv=4) → KV cache lighter than a pure transformer.
  - Architecture limit is 262144 (256k), so 100k is safely in range.
- Not confirmed:
  - Actual speed degradation in long (50k+ token) conversations — to feel in real use.
  - Whether the Node proxy (11435, ollamacacheproxy) overrides num_ctx itself — from current behavior the model parameter appears to win.
- Next: proceed to stage 5 (capture + USB output connection).

## 2026-10-09 Rule reorg 2nd (merging finished) — remaining To-bes settled

- Merged the user's delegation instruction (full delegation · full throttle) into the **actual rule clauses** (previously only a priority declaration in section 0; now the clauses themselves revised):
  - `RULES.md` section 0: removed the section-2 conflict-reference wording.
  - Section 1: "ask the user when SPEC is ambiguous" → pick a plan that fits the user's goal yourself, proceed with an `[AI judgment]` tag (50:50 blockers are the only exception).
  - Section 2: "do not move on before the user confirms" → after build success + PROGRESS update + report, go straight to the next stage without confirmation.
  - Section 4: "ask the user first" (dependencies · permissions · SDK · ABI · design outside SPEC) → decide yourself + report with an `[AI judgment]` tag and rationale. Kept only one exception: USB-path bit-perfect damage is a core product value, so wait for the user's decision after reporting.
  - Section 4 SPEC item 5: "implement after the user chooses" → updated to already decided (A+D).
  - Section 7: commit trigger "when the user confirms" → commit immediately on stage completion (build success + PROGRESS update).
  - `docs/SPEC.md` work rules: same merge for the USB clause above.
  - `docs/PROGRESS.md`: duplicate headings removed, "currently waiting: A/B/C/D selection" marked discarded.
- All remaining To-bes settled:
  - **Stage-3 candidate selection** → discarded (A+D already decided, item above).
  - **Clean up dead JNI (`nativeFdValue`) in `usbplay.cpp`** → folded into the stage-5 work (splittable as a separate unit under the ≤5-files rule).
  - **Stage 2/4 real-device tests** → user no response. Does not block progress (section 0); batch-integrate guidance after stage 5 completes.
- Next: **stage 5 (capture + USB output connection, foreground service)** — `RelayService.kt` passes the floats captured into the engine ring buffer and connects with the stage-4 USB output path. Includes the foreground notification (stop button) and one-time MediaProjection token handling.

## 2026-10-09 Rule reorg (delegation principle) — all To-be items decided

- Full rework of work principles per user instruction. Reflected in `RULES.md` section 0 (new) and the `SPEC.md` development-stages clauses.
- New principle: no handing over questions · judgment (only on a 50:50 tie, with a recommendation). Conflicts merged by myself under "minimize intervention · maximize autonomy · logical integrity". Judgments I add carry an `[AI judgment]` tag. Stages continue without user confirmation; real-device non-response does not block progress.
- Existing To-be items decided by me (below):
  - **Stage-3 resolution candidates (A/B/C/D) → A+D chosen.** USB: tolerate the original leaking softly to the phone speaker, volume 0 forbidden. BT: A2DP output is our copy, original to the speaker. Rationale: B (Shizuku) · C (UAPP redesign) don't fit a stock APK distribution; A+D is the best within SPEC scope. To be reflected in stage 5.
  - **compileSdk 36 → 37.** The actually installed SDK · stub is 37 and stage 4 already built successfully with 37. SPEC synced.
  - **Clean up dead JNI (`nativeFdValue`) in `usbplay.cpp`** → handled in the next unit.
- Remaining in progress: stage 2/4 real-device tests user no response → batch-integrate later.
- Next: full throttle to **stage 5 (capture + USB output connection, foreground service)**.

## 2026-10-09 Stage 4 (USB output test) — build succeeded, awaiting real-device test

- Current stage: stage 4 (USB output test, WAV → DAC direct playback) code complete. **BUILD SUCCESSFUL** (`gradlew.bat assembleDebug`, exit 0, APK creation confirmed).
- Done:
  - `Engine.kt` — removed the stage-2 leftover reflection-based fd-extraction code. After confirming in AOSP (`UsbDeviceConnection.java` `native_get_fd`) that `UsbDeviceConnection.getFileDescriptor()` returns an **int fd**, cleaned up to pass it straight to `nativeUsbOpen(int)`.
  - `MainScreen.kt` — stage-4 USB test screen (device list · connect · WAV path · rate · play/stop/release). Two causes of the compile error:
    1. My mistake: `numConfigurations`/`numInterfaces` → the real properties are `configurationCount`/`interfaceCount` (verified with javap).
    2. Stub jar limits: `UsbManager.ACTION_USB_PERMISSION`·`EXTRA_PERMISSION_GRANTED` constants and the alt-setting collection are **not in the stub jar** → replaced with file constants using AOSP-verified string values, removed the alt-setting loop, and identify only by the main interface `interfaceClass==1 && interfaceSubclass==2`.
  - `CMakeLists.txt` — upstream libusb does not compile without `PLATFORM_POSIX`/`DEFAULT_VISIBILITY=` macros. Added 2 `target_compile_definitions`. libusb v1.0.27 (vendored) built as static `libusb_core` and linked into `libengine.so`.
- Environment finding (honest report): this project's **compile-time android.jar is a stub, not the standard SDK** (6,440 classes, `compileSdk { version = release(37) }`). Some standard-jar members are missing, so values were verified against AOSP source and worked around as constants/properties. **SPEC says compileSdk 36 but current is 37** — mismatch.
- Remaining:
  - Real-device test (see report below). If it passes, the stage-3 resolution candidates (A/B/C/D) are reflected in stage 5 (capture + USB connection).
  - `nativeFdValue` JNI in `usbplay.cpp` is now dead code (Kotlin `external` removed). Left this time to keep the ≤5-files rule — clean up later.
- Not verified (to check on real device):
  - Whether `getFileDescriptor()` returns a valid int fd on a real device (per AOSP yes; device check)
  - Whether the hardcoded `ACTION_USB_PERMISSION`/`EXTRA_PERMISSION_GRANTED` strings match at runtime (per AOSP yes)
  - Whether the DAC is still detected properly after removing the alt-setting loop (class/subclass check should suffice)
- Real-device test result: (none yet — user no response)

## 2026-10-09 Stage 3 wrap-up — re-analysis halted, current state finalized

- The re-analysis (different chat, qwen 3.8) was halted midway. No new results in PROGRESS.md → this entry supersedes it.
- The re-analysis is an "accuracy correction" only; it is **not a blocker for the user's choice (A/B/C/D)**. The conclusions were already finalized.
- Valid conclusions (verified against local AOSP source, not guesses):
  1. Not USB > BLE hardcoded. **LIFO (last connected wins)**.
     - Basis: `Engine.cpp` 371-380 (`C:\Users\odinn\AppData\Local\Temp\aosp_verify\Engine.cpp`) — if `getLastRemovableMediaDevices().size() > 0` then `getFirstDevicesFromTypes(getLastRemovableMediaDevices())`; `NO_BT_A2DP` → `GROUP_WIRED`.
     - `LastRemovableMediaDevices` is `begin()` insert (= front) = LIFO. android13-release.
  2. LIFO only decides **where the original leaks to**; as long as capture preserves the original playback (ROUTE_FLAG_RENDER), it **cannot eliminate the duplicate output itself**.
  3. Error in the earlier "2x output" table: two **independent variables** were merged into one.
     - Variable 1 = original leak location (audio policy LIFO). Variable 2 = our copy's output (USB|BT, our choice).
     - The "❌ same bus = no 2x" row was unfounded. If we claim the DAC interface with claimInterface, the system cannot use that USB output → "original USB" and "our USB" cannot coexist; the original falls back to another output.
- Core unresolved (finalized only by real-device test; code analysis is inference only):
  - When we claim the DAC interface via libusb `claimInterface(force=true)`, does the system (USB audio HAL + AudioPolicyManager) keep that USB output "available" or fall back?
  - Internal structure of AudioFlinger.cpp / AudioMixer.cpp (mixerThread, TeeBufferProvider placement, output handling after claimInterface) unverified (halted).
- ~~Currently waiting: user choice among stage-3 candidates A/B/C/D~~ → **discarded (2026-10-09)**: self-decided A+D under the delegation principle (see SPEC development stage 3).
- Next:
  - Once a candidate is chosen, implement in stage 4 (USB output test).
  - The re-analysis can be restarted in a separate conversation if desired (not a blocker).

## 2026-10-08 Stage 3 supplement: USB vs A2DP routing (LIFO) — summary

- Purpose: confirm "where the original leaks to" at the routing level for stage 3 (duplicate output).
- Conclusion: LIFO (last connected wins). Basis `Engine.cpp` 371-380, `LastRemovableMediaDevices` (begin() insert). android13-release.
- The "2x output" scenario table merged two variables (original landing × our choice) — error → corrected in the 2026-10-09 entry above.
- Incomplete: AudioFlinger.cpp / AudioMixer.cpp internal structure — halted by gitiles fetch timeout, unverified.
- Reference: local sources at `C:\Users\odinn\AppData\Local\Temp\aosp_verify\` (Engine.cpp, AudioFlinger.cpp, AudioMixer.cpp).

## 2026-10-08 Work environment: context 80k management rule introduced

- Added `RULES.md` section 8 "Context management (80k)": 1 stage = 1 conversation; a new conversation reads only the latest PROGRESS entry; near the limit, split and finish up.
- `PROGRESS.md`: compressed 2 old detailed records into one-line summaries (see "Previous records (summary)" at the bottom of this file).
- Confirmed: `.claude/settings.json` already had the 80k model · cap settings (unchanged).

## 2026-10-08 Stage 3 (original-sound duplicate output investigation) — investigation done, awaiting user choice

- Current stage: stage 3 investigation complete. SPEC rule item 5 → implement after the user chooses from the investigation report.
- Confirmed (real AOSP source downloaded and inspected directly, not guesses):
  1. Capture does not remove the original sound. AudioPlaybackCapture applies `ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER` flags, keeping the original playback as-is and only making a tap.
     - Basis: developer.android.com/media/platform/av-capture, AOSP AudioTrack.cpp (checked in a previous session)
  2. The capture point is before volume application. AOSP `media/libaudioprocessing/AudioMixer.cpp` (main branch) `reconfigureBufferProviders()`: the buffer-provider chain is `input → TeeBufferProvider (capture tap) → channel adjust → resample → downmix`. The capture tap sits before resampling · volume · mixing → it receives the signal as the app sent it (favorable for bit-perfect).
     - Basis: android.googlesource.com/platform/frameworks/av main media/libaudioprocessing/AudioMixer.cpp (directly downloaded and checked this session)
  3. However, if the track volume is 0, capture is 0 too. Same file `postProcess()`: `if (t->teeBuffer != nullptr && t->volumeRL == 0) memset(teeBuffer, 0, ...)` — "Need to mute tee". I.e. setting media volume 0 (mute) kills the capture entirely.
     - Same file.
  4. Stock Android has no public API to lower per-app volume. The hidden interface `IPlayer.setVolume()` needs Shizuku/root/privileged apps. (Checked previous session: github.com/farizanjum/mixer-1, github.com/FuckDroidVendors/IndividualAppVolumeControl, etc.)
- Conclusions:
  - USB path: the original sound always leaks to the phone's default output (speaker/earphone). Claiming the DAC interface does not remove the original. Volume 0 kills the capture, so "volume minimal (not 0)" is the only cushion.
  - Bluetooth path: worse. Original (A2DP) and our copy (A2DP) overlap on the same device → 2x output.
- Resolution candidates (user to choose):
  A. Tolerate — guide that "the original goes out softly to the phone speaker". Volume 0 forbidden, minimal only.
  B. Shizuku-based — on Shizuku devices, per-app volume 0 via hidden IPlayer. Not possible with a stock APK.
  C. UAPP-method redesign — we become the source (decoder + custom USB). Outside SPEC. User's decision.
  D. (BT path) A2DP output device for our copy only, original to the phone speaker. But the original goes out via the phone speaker.
- Remaining:
  - ~~Implement in stage 4 (USB output test) per the candidate the user picks.~~ → discarded: self-decided A+D (2026-10-09), reflected in stage 5.
- Not verified:
  - The actual size and perception of the leaking sound at "volume minimal" on a real device (real-device test needed)
  - Whether a specific ROM (One UI etc.) behaves differently (One UI has a per-app volume UI, so check on a real device)

## 2026-10-07 Stage 2 (capture test) — code complete, awaiting real-device test

- Current stage: stage 2 code complete. `gradlew.bat assembleDebug` succeeded (APK creation confirmed).
- Done:
  - Fixed the build error in `RelayService.kt`. In the API 37 (android-37.0 stub, verified directly with javap), the `AudioRecord` API had been redesigned:
    - `AudioRecord.Builder` lacks `setSampleRate()`/`setChannelMask()`/`setPlaybackCaptureConfiguration()` → pass sample rate · channel · encoding via `AudioFormat.Builder`, use `setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration)`
    - `read(float[], int, int)` was removed → use `read(float[], int, int, flags)` + `AudioRecord.READ_BLOCKING`
    - `getMinBufferSize`, `getState()`, `release()`, `stop()`, `MediaRecorder.AudioSource.DEFAULT` (int) unchanged
  - Added the `CAPTURE_AUDIO_OUTPUT` permission to `AndroidManifest.xml` (required for playback capture, per the AudioRecord docs)
- Remaining:
  - Real-device test (user instruction: continue development excluding the case of connecting a device, but tests are on hold)
    1. Play a 44.1kHz track on the target app
    2. In the app "Start capture" → agree to MediaProjection → 30 seconds during sound playback
    3. "Stop capture" → `adb pull /storage/emulated/0/Music/bitperfect/`
    4. Bit-compare the WAV against the original (SMPTE/sox `cmp` or Audacity overlay)
  - Stage 3 (original-sound duplicate output investigation) — no real device needed, proceed next
- Additional fixes (same session):
  - Verified the official docs (developer.android.com/media/platform/av-capture): the required permission is `RECORD_AUDIO` (dangerous permission, runtime request). Removed `CAPTURE_AUDIO_OUTPUT` as a signature permission.
  - `MainScreen.kt`: added the runtime request for RECORD_AUDIO + POST_NOTIFICATIONS before "Start capture"
  - `RelayService.kt`: registered `MediaProjection.Callback.onStop` (if the user releases from Quick Settings, capture ends immediately)
  - API compatibility check: `setAudioPlaybackCaptureConfig` has existed since API 29 (per the docs), with the same signature in the android-33 stub → minSdk 33 compatible
- Unconfirmed:
  - ~~API 37 redesign~~ → misunderstanding. The Builder is identical to the API 33 stub. Runtime behavior to be verified with a real-device test
- Note: user instructed "continue development excluding the case of connecting a real device, to the next stage until a stop command is issued".

## Previous records (summary)

- 2026-10-06 stage 2 code writing: created `WavWriter.kt`·`MainScreen.kt`·`RelayService.kt`, installed compileSdk 37 · NDK 28 — replaced by the 10-07 entry above (build success)
- 2026-10-06 log start: confirmed stage-1 skeleton files, created git repo (main) and first commit — stage 1 complete (build success, verified install · execution on real device)