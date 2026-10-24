# Progress log

New entries: prepend below this line (top of file). Qwen-era log (2026-10-05 to 2026-10-11): `docs/archive/qwen-era/PROGRESS.qwen.md` (claims unverified).

## 2026-10-24 Signed release build

- Release keystore created outside the repository (`~/.android/bitperfect-relay/`: `release.jks`, `keystore.properties`), backed up to Google Drive (`내 드라이브/claude/keys/bitperfect-relay/`, hashes checked). `app/build.gradle.kts` reads it; without it the release build is unsigned. `.gitignore` excludes `*.jks`, `*.keystore`, `keystore.properties`.
- The first signed release crashed on Start: R8 limited to androidx/kotlin (`optimization.packageScope`) left an app class extending a repackaged, package-private `kotlin.text` class (IllegalAccessError). Fixed properly with whole-app R8 and the default Android keep rules (native method names kept; the app has no reflection or JNI callbacks). APK 2.5 MB.
- Debug extras (test tone, stop, volume-key test, mixer check, system-path check) now only work in debuggable builds; checked that a release build ignores the test-tone extra.
- Verified on the phone with the R8 release (BTR17, TIDAL): main screen, app picker, diagnostics popup, settings screen; relay start (permissions and battery exemption re-granted after the reinstall), all five views, visualizer settings dialog, live rate change 96 -> 44.1 kHz, stop with the music level restored; no crashes. The debug-to-release switch needed one uninstall (settings reset; app list, permissions and the USB music level 6 were put back).
- Next: publish to GitHub (public, `bitperfect-relay`, single fresh commit, MIT, noreply author, UAPP reverse-engineering notes kept local only) when the user says so.

## 2026-10-24 Goal A complete (user)

- The user declared the app work complete on 2026-10-24: the defined flow (capture -> USB DAC relay, volume, unplug handling, live rate change, relay card with visualizer) works on the S25 FE with the iDSD Valkyrie, FiiO BTR17 and FiiO Air Link. No open items in the plan.
- Kept as is by decision: start pause of about 3.5 s. Dropped by the user: optional upsampling, the "사용" switch's screen-reader state, Bluetooth output.
- If a problem shows up later (for example in long listening), debug it from the user's report; start from the latest entries here and `docs/ref/`.
- Goal B (Qwen and Gemini as auxiliary tools) dropped by the user the same day: all coding is done by Claude now. Deleted: `docs/eval/`, `tools/qwen/`, `tools/gemini/`, `docs/TEST-PROTOCOL.md`, `AGENTS.md`, `docs/testplans/`, `docs/reports/` (Gemini test runs); git history keeps them. `RULES.md` sections 1, 2, 3, 10, 11, 12 no longer mention them. `docs/archive/qwen-era/` stays as history of the early code.

## 2026-10-24 BTR17 live rate change; open items trimmed by the user

- Verified on the phone (BTR17, UAC2, TIDAL): live rate change 96 -> 44.1 -> 48 -> 192 -> 96 kHz; each switch 1.1 to 1.3 s of silence (UAC2 clock set and settle), clock read back equals the new rate, then underruns 0, err 0, capture and DAC rates match.
- User, 2026-10-24: the VU meter (0.6 s default) is liked; Air Link unplug and re-plug restores volumes fine (pending USB level confirmed); long listening shows no problem so far (debug later if one appears).
- Dropped from the plan (user): the "사용" switch's screen-reader state; the user does not use a phone screen reader.
- User, after this entry: with the BTR17 the view looks in sync with the sound at the default offset 0 (view at DAC time). The earlier "view later than the sound" was not reproduced.
- Bluetooth transmitters (Air Link) may need a positive sync offset; the slider is there.
- Dropped (user, 2026-10-24): optional upsampling (SPEC item 10).
- Start pause of about 3.5 s: kept as is (user does not find it inconvenient). Why it exists: AudioFlinger closes an idle output 3 s after its last write (kDefaultStandbyTimeInNsecs); the DAC is claimed only after the system's USB output has closed, else the capture breaks up (2026-10-11). An app cannot see or force that close, so only a few tenths of a second could be shaved, at the risk of that bug. No open items left.

## 2026-10-24 Visualizer feedback: calmer views, heavier needle, sync, color settings

- User feedback per form: 1 too dense, 2 changes too fast, 4 bar only goes right and the view seemed later than the Bluetooth sound, 5 needle too quick and twitchy; wants user colors.
- Changed: `ui/Visualizer.kt` (scroll: 3 s, peak envelope faint with the average level solid; scope: new trace every 50 ms with fading afterglow; VU: response time setting, default 0.6 s, smoothed drive; views delayed to the DAC output time plus a user offset; small view buffer instead of copying the whole history), `ui/VizSettingsDialog.kt` (new), `ui/MainScreen.kt` (settings button, user preset), `service/RelayMeter.kt` (longer history, copy with an offset), `data/AppSettings.kt`, `strings.xml`. Details in `docs/ref/ui-spec.md`.
- Answers given: the scope changing fast is normal for a scope (now calmed); the correlation bar going right is normal (L and R alike); by the numbers the old view led the sound by about 0.3 s plus the Bluetooth delay, so "later than the sound" is not explained yet; the sync offset lets the user tune it and report the value.
- Verified on the phone: scroll view shows the beat (peaks faint, average solid); scope afterglow; settings dialog opens, hue slider recolors the left channel and selects 사용자 (test colors removed afterwards); relay runs, delay about 290 ms. Build passes.
- Not verified: the needle feel and sync in the user's eyes and ears; "사용자" picked before any edit (fixed after the device run, build only).

## 2026-10-24 Relay card: visualizer, VU needle, live rate change

- User requests: no app switching while relaying (it leaked), a waveform and delay view in the app-selection place, rate change during playback even with a brief pause. Details in `docs/ref/ui-spec.md` ("Card 1 while relaying").
- Changed: `ui/Visualizer.kt` (new: 5 forms, 6 color presets, swipe gestures, VU needle physics, FFT), `service/RelayMeter.kt` (new: read-only sample copy, 5 ms block summaries, delay figure), `ui/MainScreen.kt` (RelayCard replaces the app list while relaying, RateButton extracted, DAC label uses the live relay rate), `service/RelayService.kt` (ACTION_SET_RATE, `switchRate` in the capture loop, `relayRate` flow, `buildRecord` split from `openCapture`), `output/usb/UsbDacOutput.kt` (`changeRate`: stop pump, alt and rate, start pump with interfaces kept; `bufferedMs`; `selectAlt`/`startPump` split from `start`), `data/AppSettings.kt` (viz form and preset), `strings.xml`.
- Verified on the phone (Air Link, TIDAL): the relay card replaces the list and the list returns after Stop; all five forms render and swipe in order; vertical swipe cycles presets and is remembered; delay reads about 280 to 300 ms; live rate change 48 -> 44.1 -> 96 -> 44.1 kHz, each 0.6 to 0.8 s, then underruns 0, err 0, capture and DAC rates match the new rate; DAC label follows. Stop restored the music index to its pre-start value. Build and unit tests pass.
- Not verified: the needle's feel and the visuals in the user's eyes (screenshots only); TalkBack custom actions; rate change on a UAC2 DAC (BTR17); large font with the relay card.

## 2026-10-24 Unplug during relay: stop the player, then unmute (user's order)

- Cause of the leak on unplug: the anchor keeps the target app on the muted speaker, so unplugging the DAC sends no "becoming noisy"; the app kept playing and the unmute let it out of the speaker. Also, restoring the saved index after an unplug wrote the DAC's level onto the speaker.
- Changed: `RelayService` (detach flag set by the detach broadcast or by a transfer error when the device leaves the list within 500 ms; `stopAfterDetach`: permanent audio focus so the player stops, stop USB and anchor, wait until `isMusicActive` is false, max 3 s [AI judgment: an app ignoring focus must not keep the phone muted], then unmute, then abandon focus; the detach stop runs off the main thread), `MediaMute` (`release(deviceGone)`: unmute only, keep the DAC's level pending; applied at the next engage, or at app start when the media route is on USB), `UsbDacOutput.device` public.
- Verified on the phone (Air Link, TIDAL, user unplugged): TIDAL lost focus 0.8 s after the unplug, music inactive 108 ms later, unmute after that; TIDAL left PAUSED. Physical volume keys during this relay reached our session and changed the Air Link level (log and user's BT earphones). Build and unit tests pass.
- User confirmed afterwards: the phone speaker volume is back to its own level after an unplug, both with the app closed and open; on re-plug the app restores the dongle's previous hardware level (log: `USB volume: restored -4352`). The pending music level was used at the next engage (log 17:43:56: saved 15, not the muted 2).
- Not verified: the pending USB music index applied at app start with the DAC attached (no relay); until then system playback on the DAC uses index 2.
- Earlier the user saw the physical keys change the phone's volume instead (17:33, the stream was re-muted each time); not reproduced since.

## 2026-10-24 Air Link: hardware volume works; fix volume restore race and stale DAC facts

- Device: FiiO Air Link (UAC1 BT transmitter) on the S25 FE; it has a Feature Unit volume (-50 to 0 dB, 1 dB), details in `docs/ref/volume.md`.
- Verified on the phone: volume keys, slider tap, + button and notification -/+ each SET the level and read it back equal; relay 44.1 kHz underruns 0, err 0. DAC level left at 0 dB (its level before the test).
- Found and fixed: (1) after a long relay with volume keys, Stop restored the music index onto the speaker (speaker 8 to 15, USB left at 2) because the target app's track was still on the speaker; `RelayService.awaitMediaRouteOffSpeaker` now also waits for active playback to leave the speaker. Re-run: 3 short cycles and 1 cycle of 60 s with volume keys, notification shade and scrolling, USB index back to 15 each time (the long one waited 110 ms). (2) After swapping BTR17 for the Air Link in the same app process, the card kept BTR17's "32bit/192kHz · DSD" until permission; `MainScreen` now clears the previous DAC facts first. After the grant it showed "24bit/96kHz". The swap itself was not repeated after the fix (the reinstall restarted the process).
- Side effect left on the phone: speaker music volume is 15 (was 8) from the failed restore; the user was told.
- Not verified: audible check through BT headphones on the Air Link.

## 2026-10-24 BTR17 check: volume restore, rate label; fix Start without USB permission

- Found on the S25 FE + BTR17: a DAC plugged in while the app was closed (or after a reinstall) has no USB permission, yet Start went ahead and the service stopped with a SecurityException. Fixed in `ui/MainScreen.kt`: Start asks for USB permission first and continues the start once granted; the DAC is matched by vendor/product (serial unreadable before the grant) and the service gets the id with the serial read after the grant.
- Verified on the phone (Wi-Fi adb, TIDAL playing): after a reinstall, Start shows the USB prompt, then the capture consent, then relays (44.1 kHz, underruns 0, err 0). While relaying the DAC name reads "FIIO BTR17 · 44.1kHz"; after Stop it reads "FIIO BTR17". Stop restored the USB headset music index to 15 (was 15 before start, 2 while relaying) and the system music output went back to the USB headset. Build and unit tests pass.
- Not verified: Deny on the new USB prompt (same receiver path as before); a DAC with a Feature Unit.
- Next: Feature Unit DAC (iDSD Valkyrie) for SET/GET and the slider.

## 2026-10-24 Phone check of the UI add-ons (no DAC) + night-mode background

- Verified on the S25 FE (screenshots and uiautomator): Spotify shows "캡처 차단"; the battery exemption dialog appears on the first start only (exemption registered in `dumpsys deviceidle whitelist`); main and settings screens intact at font scale 2.0 (font scale restored to 1.15).
- Fixed: in night mode the page was white under light status-bar icons because the XML window theme is light and Compose painted no background; `BitPerfectTheme` now wraps content in a background `Surface` (checked on the phone). Settings text no longer mentions Bluetooth.
- Still open: volume restore after stop on a USB DAC; output-rate label next to the DAC name.

## 2026-10-24 UI add-ons: capture-blocked label, battery exemption, status live region, large font

- Task: `docs/tasks/2026-10-24_ui-addons.md` (subagent). Decisions in `docs/ref/ui-spec.md` (card 1, output-rate info, accessibility, battery optimization).
- Changed: `capture/CaptureCheck.kt` (new), `ui/MainScreen.kt` (blocked label in list and add-app popup, battery request on the first Start, status live region, DAC name with relay rate while relaying, "44.1kHz" formatting, min heights, stacked app row above font 1.3), `MainActivity.kt` (safeDrawingPadding), `ui/SettingsScreen.kt` (back button min height), `data/AppSettings.kt` (`batteryAsked`), `AndroidManifest.xml` (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), `strings.xml`; tests `CaptureCheckTest` (new, unit), `androidTest/StatusLiveRegionTest` (new, instrumented).
- Verified: `./gradlew assembleDebug testDebugUnitTest` (57 tests pass). API 36 emulator (no audio): Chrome (manifest `allowAudioPlaybackCapture=false`, confirmed with aapt2) shows "캡처 차단" in the popup and the list, stays selectable, TalkBack reads "Chrome, 미선택, 캡처 차단" (TalkBack speech log); other launcher apps unmarked. Known-list path (Spotify) by unit test only. Battery: system dialog on the first Start tap, start continued after Deny, no dialog on the second tap or after an app restart. Status node exports `liveRegion = POLITE` (instrumented test via UiAutomation, run with `am instrument` on the emulator only). Font 1.3 and 2.0: main and settings screens checked by screenshots after the fixes.
- Not verified: TalkBack actually speaking a status change (with TTS disabled to log speech, TalkBack kept turning itself off); output-rate text next to the DAC name (needs a DAC and a running relay); Spotify label on a real device.
- Next: on the phone with a DAC, check the "· 44.1kHz" next to the DAC name while relaying.

## 2026-10-24 USB queue and start order, volume (hardware, digital, keys), diagnostics popup, capture hint

- Task: `docs/tasks/2026-10-24_driver-volume-diag.md` (subagent). Details: `docs/ref/usb-dac.md` (2026-10-24 findings), `docs/ref/volume.md` (new).
- Changed: native `xfer_plan.h` (new), `usbplay.cpp/.h` (planned queue, stall watchdog with recovery, native log ring, 16 stats), `engine.cpp`; Kotlin `UacDescriptors` (AC topology, Feature Units), `UacVolume` (new), `UacControl` (clock-valid retry), `PcmPacker` (integer gain), `UsbDacOutput` (UAPP start order, volume setup, capture-silence flag, diagnostics facts), `TestTone` (source only), `RelayService` (tone through the relay sequence, volume-key media session, notification -/+), `RelayVolume` (new), `MediaMute` (re-mute on AudioService broadcasts), `diag/AppLog`, `diag/Diagnostics`, `diag/SystemPathCheck` (new), `MainActivity` (debug extras also via onNewIntent), `MainScreen` (volume card, capture hint, diagnostics popup), `SettingsScreen` (digital volume switch), `AppSettings`, `strings.xml`; tests `UacVolumeTest` (new), `PcmPackerTest`, `usb_native_test.cpp`.
- Verified: `./gradlew assembleDebug testDebugUnitTest` (53 tests pass); native tests ALL PASS on the phone. On S25 FE + BTR17: tone through the relay sequence (underruns 0, err 0) and the system plays on the DAC afterwards; relay Apple Music at 32 ms in flight for at least 45 s with underruns 0, err 0 through pause, resume, skip; BTR17 has no Feature Unit (descriptors), UI offers digital volume; diagnostics popup opens and copies (clipboard read back 6668 chars); capture hint shown after 5 s of silence; volume keys reach our session screen on and off, also after track skips, music stream stays muted (AudioService event log).
- Found: 50 ms or more in flight stalls the stream for good when the music app resumes, but only while a BT headset is active on the phone (system USB audio path touches the DAC). Re-test without it (main conversation): 100 ms clean, user heard TIDAL clean and the 44.1 kHz tone steady (the old wavering is gone). [AI judgment] default 100 ms. Volume keys used to unmute the music stream after a track skip or with the screen off; fixed (session priority re-assert, MediaMute broadcasts).
- Not verified: stall-recovery path (the BTR17 drop at one stop was its own idle auto-off, user); slider and notification -/+ changing a real DAC level (BTR17 has none); digital volume audible effect; volume keys with a real USB relay attached (same code path, tested with the debug harness); sound quality (no listener).
- Fix after review (main conversation): stop restored the saved music volume while the speaker anchor still held the route, so the index went to the speaker and the USB DAC stayed at 2 (music volume is per device; seen on the device). `RelayService.stopUsb` now stops the anchor, waits up to 2 s for the media route to leave the speaker (`getAudioDevicesForAttributes`), then restores. Build and unit tests pass; not verified on the device yet (phone battery ran out).
- Next: verify the volume restore after stop on the BTR17; test a DAC with a Feature Unit (iDSD Valkyrie?) for SET/GET and the slider.

## 2026-10-13 Docs: build and shell commands that actually run on this PC

- Why: a transcript survey (Claude, Qwen, Gemini sessions) found the bare `gradlew.bat assembleDebug` failing in Git Bash 13 times across 6 sessions, and Gemini in Antigravity failing on the "Shell: Git Bash" test plans because its terminal tool is PowerShell 5.1.
- Changed: `RULES.md` §10 and `docs/SPEC.md` build to `./gradlew` (PowerShell: `.\gradlew.bat`), SPEC uses the full adb path; `docs/TEST-PROTOCOL.md` §2 build and §7 how Antigravity runs a plan line (`$RUN/_cmd.sh` + Git Bash by full path with `-o igncr`); STAGE6/STAGE7 plans build with `./gradlew`; allow-lists in `.claude/settings.json` and `tools/qwen/claude-settings.qwen.json`.
- Verified: `./gradlew --version` (Git Bash) and `.\gradlew.bat --version` (PowerShell) exit 0; the `_cmd.sh` wrapper ran from PowerShell 5.1 with CRLF lines, Korean text, double quotes and `$` variables intact. Not verified: a full `assembleDebug` or a Gemini test run with the new wording.
- Next (unchanged from the Phase 1 entry below): Bluetooth feasibility test; long-run drift listening; live diagnostics panel; switch accessibility state (low priority); start pause tuning; optional upsampling via the settings library swap.

## 2026-10-11 Phase 1 complete (user): USB DAC relay works on the user's phone

- State: capture -> USB DAC relay plays clean on Galaxy S23 FE + iDSD Valkyrie (user listening), no speaker leak, system DAC usable again after Stop, opening the app does not disturb system audio. Bluetooth output removed. Test APK and Korean guide for the user's friend in `dist/` (not tracked; user reports sound quality only).
- Phone left with: Wi-Fi adb switched back to USB mode; debug props reset. The last relay may still be running (music stream muted) because the Stop tap failed; the user was asked to press Stop or reopen the app (MediaMute restores on start).
- Open items for the next phase, in the user's order: Bluetooth feasibility test (speaker anchor moves the original off the BT headset; open problem: muting the speaker without muting a BT relay, candidates: per-device speaker volume 0, or a non-media usage for the relay); long-run drift listening; live diagnostics panel (user request); switch accessibility state (low priority; user does not use a phone screen reader); start pause of about 3.5 s could be tuned; optional upsampling via the settings library swap (later, user).
- Diagnostics remain in the build (rate log, test tone, debug props, debug_stop/debug_mixer extras); remove or gate before a public release.

## 2026-10-11 USB relay start/stop sequence fixed (focus + mute + speaker anchor)

- Correction: the earlier "speaker anchor alone" success was misattributed. That run started right after UAPP, with the system already playing on the speaker; on a clean re-plug the anchor alone did not move the output (Tidal kept playing on the USB route) and the capture gaps returned.
- Working sequence (`RelayService`, off the main thread): request transient audio focus (the target app pauses) -> MediaMute -> SpeakerAnchor (now the only media client, so the shared output moves to the speaker) -> wait 3.5 s -> claim the DAC and start USB -> start capture -> abandon focus (target resumes on the muted speaker route). UAPP also takes focus when it plays.
- Results on the device (clean re-plug each time): zeroGaps stays 1, no speaker leak at start (mute before the move), user hears clean audio on the Valkyrie; after Stop the system plays on the Valkyrie again without a re-plug (no HAL skip writes after stop). Kernel re-bind returning EBUSY is benign: the kernel re-binds snd-usb-audio itself.
- Also fixed: the probe no longer claims the DAC (opening the app no longer cuts system audio); DAC facts come from a cache written at relay start, else from the system USB output rates. Relay running state lives in `RelayService.relaying` (audit defect 19); the stop status keeps the failure reason.
- Not verified: long-run drift; accessibility state of the "use" switch (still reads unchecked).
- Next: switch accessibility state, then the test APK for the user's friend (sound quality only), then the Bluetooth feasibility test.

## 2026-10-11 Clean relay without settings: speaker anchor (UAPP method)

- User rejected the developer-option fix (developer mode breaks some apps, users rarely enable it). Samsung "앱 사운드 분리" pinned Tidal to the speaker in its settings but did not override the USB route. The bit-perfect mixer API (API 34) is not offered on this phone (`getSupportedMixerAttributes`: 30 entries, all MIXER_BEHAVIOR_DEFAULT).
- Found UAPP's method by running it with adb watching: it plays a silent USAGE_MEDIA track with `setPreferredDevice(built-in speaker)`, which moves the shared media output off the detached USB route.
- New `service/SpeakerAnchor.kt`, started with the USB relay, stopped on stop. Result: zeroGaps stays at 1 (start only) for 70 s, no HAL skip logs, sound only from the DAC (user). Works with routing setting off and no Samsung feature.
- AOSP facts (frameworks/base main, `UsbAlsaManager.java`, `UsbHostManager.java`): USB audio is deregistered only on USB detach; an ALSA card removal is not acted on; the attach broadcast goes out just before USB audio registration.
- Supersedes the routing-setting entry below.

## 2026-10-11 Clean relay: Android USB audio routing must be disabled

- Remaining dropouts after the feedback fix came from the capture, not USB: the captured stream had about 2 gaps of exact digital silence per second (new diagnostic `zeroGaps` in `USB rates:`). Cause: after we take the DAC, Android still routes the target app to the detached USB output (Samsung HAL logs "skip ... to write by device disconnection"), and playback capture follows that output's broken cadence.
- Fix (user action): Developer options "USB 오디오 연결 사용 중지" = `Settings.Secure usb_audio_automatic_routing_disabled` (AOSP Settings.java, @hide @Readable). With it on and the DAC re-plugged, Tidal routes to the speaker (muted by MediaMute), zeroGaps stays at 1 (start only), and the user hears clean audio at 44.1 kHz.
- Test tone at 44.1 and 48 kHz was clean (USB path fine). Tidal relay at 44.1 kHz clean after the setting.
- Next: app check of that setting with guidance in the UI; long-run listening for drift (no usable feedback on the Valkyrie); then open defects (re-bind EBUSY, probe on app start, running state, switch semantics) and the live diagnostics panel. Docs to write: `docs/ref/usb-dac.md` findings (feedback window, routing setting).

## 2026-10-11 First USB DAC sound (iDSD Valkyrie); feedback fix

- Real device: relay to iFi iDSD Valkyrie (UAC2, high speed, async) plays; user confirms bit-perfect-sounding audio.
- Dropouts found and fixed: the Valkyrie reports feedback about 1.3% above nominal (steady, same for 16/24/32-bit, 44.1/48/96 kHz). Following it overflows the DAC (crackle); the exact nominal rate plays clean (test tone, user listening). `iso_clock.h` now accepts feedback only within 0.5% of nominal (Linux accepts -12.5..+50%). Unit test added; native tests pass on the phone.
- Diagnostics added: rate log every 5 s (`USB rates:` capture vs DAC frame rate, ring, feedback raw/len, rejected count), test tone without capture (`am start ... --ei debug_tone_rate 48000 --ei debug_tone_sec 30`), knobs `debug.bitperfect.nofb` and `debug.bitperfect.alt`, raw descriptor dump in the probe. Wi-Fi adb at 192.168.0.25:5555 (user ran `adb tcpip 5555`).
- Also verified: music mute engages and restores; restore after a force-stop works; Valkyrie descriptor has one clock source and selector (we set the right clock; read-back matches).
- Open defects seen: (1) kernel driver re-bind fails with EBUSY, so after our probe/relay the DAC is silent for the system until re-plugged; (2) the probe runs at every app start and detaches the driver; (3) UI loses the running state (audit defect 19), Stop only from the notification; (4) the "use" switch reads as unchecked in accessibility although the selection is saved.
- Next: user listening test of the Tidal relay with the fix, a long run for drift, the internal-mixer path test (user request), then the open defects and a live diagnostics panel (user request).

## 2026-10-11 Music stream muted during USB relay (UAPP method)

- New `service/MediaMute.kt`: on USB start save the music volume, set it to 2 and mute (`adjustStreamVolume ADJUST_MUTE`, which is what `setStreamMute` calls in AOSP); a Settings observer re-applies it, so volume keys are ignored like UAPP default. Restored on stop; a mute left by a crash is undone at app start (`MainActivity`).
- SPEC: leak update and a later upsampling item (library picked in the settings swap).
- Verified: build + unit tests. Not verified: anything on a DAC (speaker silence, capture level, restore).

## 2026-10-11 Bluetooth output removed; output card is one DAC + "use" switch

- User decision: Bluetooth output removed (SPEC top, local notes). Deleted `cpp/bt/`, vendored `cpp/soxr/` and `cpp/oboe/`, `output/bt/BtA2dp.kt`, BT JNI, the soxr table in `dl/dllib`, soxr/Oboe catalog entries, `BLUETOOTH_CONNECT`, the bogus `USB_PERMISSION` (audit defect 21), and the service's switch-output action.
- UI (`MainScreen.kt`, `docs/ref/ui-spec.md`): device tree removed. Output card: "없음" without a DAC; with a DAC its facts and a "사용" switch (asks USB permission; locked while relaying). Start always enabled and says what is missing. Selection survives an unplug; re-plug asks permission again (ui-spec). A saved BT selection is cleared on load.
- USB permission result: no longer read from the extras (audit defect 20); the receiver checks `hasPermission` directly. PendingIntent is now explicit to the package.
- Verified: build + 42 JUnit tests; JNI symbols match; on the phone the new card shows "없음" and Start reports "출력 장치의 사용 스위치를 켜세요"; no crash.
- Not verified: everything with a DAC attached (switch, permission prompt, probe, re-plug, relay).
- Pending user decision: mute STREAM_MUSIC during USB relay to remove the speaker leak (local notes).

## 2026-10-11 First BT relay test on the phone (Moondrop U.C.T.S., Tidal)

- Fixed audit defect 26: missing WAKE_LOCK permission crashed every relay start (`AndroidManifest.xml`).
- Result: relay starts and runs. The user hears the music twice and broken. `dumpsys media.audio_flinger` shows why: Tidal's original track and the relay track are mixed into the same A2DP output thread (44.1 kHz, aptX 44.1/16). The capture keeps the original playing on the default output, which is the BT headset. Inherent to the capture method; product decision pending with the user.
- Also seen: double resampling (soxr 44.1 to 48 kHz, then AudioFlinger 48 to 44.1 kHz back) because of defect 22. On stop, `btplay_stop` ran three times and the capture loop's end log was missing (to check).
- BT code changes are on hold by the user's instruction.

## 2026-10-11 USB engine rewritten (Kotlin control + native byte pump); A3 tasks 1-2

- Docs: `docs/ref/uac.md` (verified UAC/usbfs/libusb fact sheet with sources), `docs/eval/qwen-common.md`, engine design in `docs/ref/usb-dac.md`. `tools/qwen/ask.py`: `--common`, `--facts`.
- Native: new C ABI `cpp/usb/usbplay.h`; `usbplay.cpp` (Qwen A3-T1, fixed), `iso_clock.h` (kernel packet sizing + feedback detection), `byte_ring.h` (SPSC). `dl/dllib` table +3 libusb calls. `engine.cpp` JNI rewritten for USB.
- Kotlin: `output/usb/UacControl.kt` (Qwen A3-T2, as-is), `UsbDacOutput.kt` (session + UI probe), parser now reads clock bmControls and CS endpoint attributes. `RelayService` uses `UsbDacOutput`; fixes audit defects 16, 17, and 18 for USB (start failure stops with the reason). `MainScreen` DAC info from the probe; old stage-4 WAV test section removed.
- Found and fixed defect 25: native libs were not extracted, so libusb/soxr never loaded (`app/build.gradle.kts` packaging).
- Verified: `gradlew testDebugUnitTest assembleDebug` pass (42 JUnit tests); native tests `tools/native-test/run.sh` pass on emulator `bp_api36`; JNI symbols match the 22 Kotlin externals; app starts on the emulator and both libraries bind.
- Not verified: any real DAC streaming (needs the user's phone and DAC), kernel driver re-bind, DSD flag, UI probe on real hardware.
- Next: real-device USB DAC test (instructions in chat 2026-10-11). Then audit defects 19, 20 (USB permission key), 22, 23, and 18 for BT. Evaluation log: `docs/eval/trials.md` (A3).

## 2026-10-11 Phase A3 decided: fact-sheet mode re-evaluation during the USB rewrite

- Verdict "Qwen not used for coding" applied only to full-brief mode. Re-evaluating in fact-sheet mode (user proposal): `docs/eval/README.md` Phase A3; RULES section 12 updated; data policy: tools may be used freely for meaningful data.
- Next session, in order: (1) write `docs/eval/qwen-common.md` and `docs/ref/uac.md` (verified UAC1/UAC2 facts with sources: descriptors, SET_CUR/RANGE requests, packet sizing, feedback formats); (2) extend `tools/qwen/ask.py` to prepend common context and fact sheets; (3) USB engine rewrite per the plan below, delegating medium/large units and logging each in `trials.md`.

## 2026-10-11 Phase A done: Qwen not used for coding; first USB rewrite parts in place

- Phase A1 audit: `docs/eval/phase-a-audit.md` (24 defects; USB engine and DAC info graded D). This is also the fix list for the app.
- Phase A2 trials: `docs/eval/trials.md`. Verdict in `docs/eval/README.md`: Phase A fails on cost, Phase B skipped. RULES section 12 updated.
- New, unit-tested (34 JUnit tests, `app/src/test/java/com/bitperfect/relay/output/usb/`): `output/usb/UacDescriptors.kt` (raw descriptor parser, UAC1 + UAC2, clock path), `PcmPacker.kt` (float to left-justified LE PCM), `UacRates.kt` (UAC2 RANGE decode, rate checks). Written by Qwen from Claude's briefs, fixed and reviewed by Claude. Not wired into the app yet.
- Verified: `gradlew testDebugUnitTest assembleDebug` pass.
- Next: USB engine rewrite. Plan: Kotlin does descriptor parsing (`getRawDescriptors`), rate query and SET_CUR (`controlTransfer`), and packing; native `usbplay` becomes a byte pump with libusb completion callbacks, nominal packet sizing per (micro)frame and a separate feedback IN endpoint. Then fix service/UI defects 16 to 20 and BT defects 22, 23.

## 2026-10-11 Handover to Claude + new project rules

- Claude now owns the project. New rules: `RULES.md` (loaded by `CLAUDE.md`). Non-Claude agents get `AGENTS.md` (do only the assigned task; Gemini follows `docs/TEST-PROTOCOL.md`).
- Archived to `docs/archive/qwen-era/`: old RULES, AGENTS, PROGRESS, an older Qwen settings copy. The latest Qwen agent settings were copied to `tools/qwen/claude-settings.qwen.json` (inactive).
- Added the evaluation program `docs/eval/README.md` (Phase A Qwen coding gate, Phase B context size, Phase C test/debug tools), the log `docs/eval/trials.md`, and the helper `tools/qwen/ask.py` (smoke test OK: `qwen3.8-coding:27b`, ~92 tok/s, 6 s load).
- Gemini method decided [AI judgment]: Claude launches it headless (`agy -p`) under an allow/deny list; IDE launch is the fallback. Details and the 2026-10-05 permission history: `tools/gemini/README.md`.
- Applied (user switched to full auto-accept): `.claude/settings.json` no longer routes to Qwen; Gemini allow/deny list installed and smoke-tested (allowed adb ran, git denied).
- Cline model-gap question answered: `docs/eval/model-variants.md`.
- App state as Qwen left it (unverified): stages 1 to 9 coded and building for arm64-v8a + x86_64; stage 7 UI passed a Gemini VM run on 2026-10-10; USB DAC streaming, BT A2DP, DAC info and library swap never ran on a device.
- Next: Phase A1, audit of the Qwen-era code (`docs/eval/phase-a-audit.md`). Bugs found there become the first fix list. Early lead to check first: the stage 8 "UAC2 Format Type I fix" parses sample rates and channel fields from the Format Type descriptor; in UAC 2.0 that descriptor is 6 bytes (subslot size, bit resolution) and rates come from Clock Source requests.
