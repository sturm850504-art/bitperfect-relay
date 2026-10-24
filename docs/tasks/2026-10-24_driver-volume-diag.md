# Task: USB driver queue/start fixes, volume control, diagnostics popup, capture-blocked hint

Date: 2026-10-24. Owner: main conversation; executed by a subagent. Effort: high (volume-key routing on Samsung is device research with an unknown answer; raised one step from the project's token-saving default for that reason). Model: default.

## Must not miss
- Read `RULES.md` first. Korean UI strings in `strings.xml`, English code/comments/docs, log tag `BitPerfect`, TalkBack descriptions, 48dp targets, no error hiding, fact-check Android/UAC behaviour against official docs or AOSP/Linux source and cite it in docs.
- Product core rule: no original-sound leakage. A volume-key method that briefly unmutes STREAM_MUSIC (so the original plays on the phone speaker, even for a few ms) is NOT allowed. If the only working key method leaks, do not ship it; record the finding and stop that sub-part.
- Relay path stays unprocessed EXCEPT digital volume, which the user allowed on 2026-10-24 under these limits: only for DACs that have no hardware (Feature Unit) volume, off by default, labeled in the UI as not bit-perfect ("비트퍼펙트 아님"). With digital volume off or at 100%, samples must pass bit-exact (unit test).
- Diagnostics: user wants a popup with the text and a copy button; they paste it into a messenger themselves. No sending, no recipient setting, no network.
- Quality bar: the defined flow works without bugs; skip edge cases.
- Do not copy UAPP code. local notes gives approach and parameters only.
- Do not commit. The main conversation reviews and commits.

## Background (read only the parts you need)
- Latest state: top entries of `docs/PROGRESS.md`; device findings `docs/ref/usb-dac.md`; UAPP approach local notes; capture leak local notes; target apps `docs/ref/target-apps.md`.
- Driver: `app/src/main/cpp/usb/usbplay.cpp` (8 data transfers, packets per transfer = pps/500, so only about 16 ms is queued; UAPP queues about 200 ms), `iso_clock.h`, `byte_ring.h`; Kotlin `app/src/main/java/com/bitperfect/relay/output/usb/` (`UsbDacOutput.kt` start order: claim, setInterface alt 0, setInterface alt, setRate, sleep 200 ms, start pump).
- Open device problem: FiiO BTR17 (VID 0x2972 PID 0x0088, UAC2 async, wMaxPacketSize 200, its feedback endpoint returns all-zero values so we play nominal) sounded wavering with our 44.1 kHz test tone while our stats were clean (underruns 0, short 0, err 0). Android's own path to the same DAC sounded steady. iDSD Valkyrie plays clean with our driver.
- `TestTone.kt` (debug intent `--ei debug_tone_rate N --ei debug_tone_sec S`) claims the DAC without the RelayService focus/mute/speaker-anchor sequence; after it the BTR17 was left in a bad state (no sound on the system path until re-plug).

## Work items, in order
1. Driver queue and start order.
   - Queue about 100 ms of audio in flight by default (more packets per transfer and/or more transfers; keep the libusb packet limit in mind), with a debug system property to override the in-flight time for A/B tests (like the existing `debug.bitperfect.*` props). Keep the ring prime/capacity consistent with the deeper queue.
   - Start order like UAPP: set the sample rate (and check clock validity when the DAC offers it) before selecting the streaming alt setting; keep alt 0 on stop.
   - Make the debug test tone go through the same USB start/stop sequence as a relay (focus, mute, speaker anchor, claim, stop and re-bind) so it cannot leave the DAC in a bad state. Simplest is to let RelayService feed a tone instead of capture when the debug extra is present.
   - Unit tests for any changed sizing math (`app/src/test/cpp/usb_native_test.cpp`, run on the phone with `tools/native-test/run.sh`).
2. Hardware volume (USB Feature Unit).
   - Parse Feature Units for UAC1 and UAC2 (in `UacDescriptors.kt`), find the one on the path to the output terminal, read volume and mute capability per channel (master and/or channels 1..n).
   - Control requests: GET RANGE/MIN/MAX/RES and CUR, SET CUR for volume (1/256 dB units) and mute. Cite the UAC1 and UAC2 spec sections or Linux `sound/usb/mixer.c` for request codes and value formats.
   - Remember the level per DAC (VID:PID) in app settings. On the first start with a DAC, read the DAC's current level and keep it (never jump to max).
3. Volume UI and keys.
   - On-screen slider (and +/- buttons) on the main screen while relaying, showing dB for hardware volume or percent for digital. TalkBack labels with the value.
   - Volume keys: try a MediaSession with remote playback (`setPlaybackToRemote` with a `VolumeProvider`) owned by RelayService while relaying. Check on the device whether the volume keys reach it while TIDAL or Apple Music plays, with screen on and with screen off (`adb shell input keyevent KEYCODE_VOLUME_UP` is fine for a first check; note that real keys with screen off are routed by the system to media sessions). Check that STREAM_MUSIC stays muted (no leak) and that play/pause media buttons still reach the music app. Read the AOSP `MediaSessionService`/`MediaSessionStack` volume routing to explain the result.
   - If keys do not reach our session without leaking, ship the slider only, plus +/- actions on the relay notification, and document the finding for the main conversation. Do not use the STREAM_MUSIC observer method (it leaks).
4. Digital volume fallback.
   - Settings switch, only enabled when the active DAC has no hardware volume; off by default; label says not bit-perfect. Applied in the packing step as integer gain; exact passthrough at 100%. Unit test both.
5. Diagnostics popup.
   - A button (settings screen or main screen; your call, tag `[AI judgment]`) opens a dialog with selectable text and a copy button (ClipboardManager). Content: app version, phone model, Android version, DAC name/VID/PID/UAC version/alt/rate/format/feedback type/hardware volume yes-no, current or last relay stats (the same fields as the "USB rates" log), last failure reason, and the last ~100 `BitPerfect` log lines kept in an in-app ring buffer (do not read system logcat).
6. Capture-blocked hint.
   - While relaying, if captured audio has been all zero for 5 s, show a main-screen hint that some apps (Spotify) block capture. Clear it when non-zero audio arrives.

## Device and test notes
- Phone: Galaxy S25 FE, Wi-Fi adb `192.168.0.25:5555`, adb at `C:/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe`. In Git Bash prefix adb with `MSYS_NO_PATHCONV=1`. Use `./gradlew assembleDebug testDebugUnitTest`.
- Connected DAC now: FiiO BTR17. After a re-plug the app's USB permission is gone; the permission dialog can be tapped with `adb shell uiautomator dump` + `input tap`. Keep the DAC volume low; no human is listening during your run, so verify digitally (stats, logs, control GET after SET).
- Music apps installed: TIDAL, Apple Music, YouTube Music. A test WAV is at `/sdcard/Music/tone1k_44k.wav`.
- After any test, stop the relay (debug intent `--ez debug_stop true` or the UI) and confirm STREAM_MUSIC is restored.

## Done criteria
- `./gradlew assembleDebug testDebugUnitTest` pass; native tests pass on the phone.
- On the BTR17: a relay with TIDAL or Apple Music runs 60 s with underruns 0, err 0, and the log shows the new in-flight time; after Stop the system plays on the DAC again without a re-plug; the debug tone run also leaves the DAC usable.
- Hardware volume: if the BTR17 has a Feature Unit volume, SET then GET returns the set value, and the slider changes it. If it has none, say so and show digital volume being offered.
- Volume keys: a clear result (works or not, screen on and off, leak or not) with the evidence.
- Diagnostics dialog opens and copies (check clipboard via a log line or uiautomator).
- Docs: update `docs/ref/usb-dac.md` (BTR17 findings, Feature Unit), add the volume-key finding to local notes or a new `docs/ref/volume.md`, add `[AI judgment]` notes, and prepend a PROGRESS entry. Add any new library to the settings library catalog.
- Final message (at most 20 lines): what changed (files), what was verified and how, what was not, and the volume-key result.

## Result
Done 2026-10-24. Reviewed: build and unit tests re-run, phone re-test without a BT headset (100 ms clean, user heard music and tone steady), default moved from 32 to 100 ms, stop volume-restore order fixed (device check pending). Volume keys result in `docs/ref/volume.md`.
