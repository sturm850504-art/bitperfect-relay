# USB DAC details (read when working on USB output)

## Connection
- Find audio-class devices and get permission via `UsbManager`.
- Pass the `UsbDeviceConnection` file descriptor to native and open with `libusb_wrap_sys_device`. libusb uses `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`.
- Detach the kernel driver with `claimInterface(force=true)`.

## Descriptor parsing
- UAC version: interface protocol value (0x20 ⇒ UAC2, otherwise UAC1)
- Supported formats:
  - UAC1: sample-rate list of the Format Type I descriptor
  - UAC2: clock source RANGE request; per alt-setting bit depth and subslot size
- DSD: UAC2 format type/bitmap + per-vendor exception list (separate JSON file; may start empty)
- Power: self-powered bit in the configuration descriptor bmAttributes + bMaxPower. Self-powered ⇒ "built-in battery", otherwise "phone power"

## Output
- Sample rate setting: UAC1 endpoint SET_CUR, UAC2 clock source SET_CUR
- Isochronous transfer. For async DACs, adjust samples-per-packet with the feedback endpoint value
- Packing to the subslot size (3/4 bytes). No resampling · mixing · volume processing.
- Implementation order: UAC2 first, then UAC1.

## Engine design (rewrite 2026-10-11)
- Kotlin `output/usb/`: `UacDescriptors` (parse `getRawDescriptors`), `UacControl` (clock path, RANGE, SET/GET CUR, alt choice), `UsbDacOutput` (claim AC + AS interfaces with force, `setInterface`, rate, start the pump, pack float to PCM with `PcmPacker`, stop: alt 0, release, re-bind the kernel driver, close).
- Native `cpp/usb/usbplay.cpp`: byte pump only. Data transfers planned by `xfer_plan.h` (since 2026-10-24: 100 ms in flight by default, at most 16 KiB and 128 packets per transfer, at least 8 transfers; was 8 transfers of about 2 ms), 2 feedback transfers of 1 packet, completion callbacks on one event thread. Packet sizes from `iso_clock.h` (kernel algorithm), data from an SPSC `byte_ring.h`. A stall watchdog cancels and re-queues the data transfers when none completes for 300 ms, and fails the stream ("DAC stopped taking audio (USB stall)") after 3 tries without progress.
- Start order (since 2026-10-24, like UAPP): claim, alt 0, UAC2 clock rate set and clock validity read (up to about 200 ms of retries) while at alt 0, then the streaming alt; UAC1 sets the endpoint rate after the alt. Stop: alt 0, release, re-bind.
- Buffering [AI judgment]: 200 ms primed before real data (the ring is raised to at least prime + 2 x in-flight time) (at start and after an underrun), 1 s ring; full ring drops the incoming chunk. No resampling, so the capture clock and the DAC clock drift: a gap of silence or a dropped chunk is expected every tens of minutes, depending on the two clocks. Counters are logged at stop (`USB stop: underruns=...`).
- Output selection rule: highest bit resolution among 2-channel PCM Type I alt settings that support the capture rate.
- DAC info for the UI comes from `UsbDacOutput.probe`: for UAC2 it briefly claims the AC interface to read RANGE, then releases it and re-binds the kernel driver.
- DSD flag [AI judgment]: a UAC2 alt setting with the raw-data bit (or the vendor list).

## UI item display
- Name, "UAC2.0"/"UAC1.0", max format ("32bit/384kHz · DSD256" format, only when known), power icon
- Power icon: bolt in phone = phone power, battery pack = built-in battery
- TalkBack description includes power text (e.g. "powered by phone").

## Device findings (real device, 2026-10-11)
- Test setup: Galaxy S23 FE (Android 16), iFi iDSD Valkyrie (VID 0x20B1 XMOS, UAC2, high speed, async; one clock source 0x29 behind selector 0x28; alt 1/2/3 = 32/24/16-bit, alt 4 raw).
- Feedback: the Valkyrie reports about 1.3% above nominal for every format and rate tried (raw 16.16, 4 bytes). Following it overflows the DAC (crackle); the exact nominal rate is clean. `iso_clock.h` accepts feedback only within 0.5% of nominal [AI judgment; Linux accepts -12.5..+50%]. Without usable feedback, phone and DAC clock drift is not tracked; a long-run listening test decides whether this matters.
- Routing: after the app claims the DAC, Android keeps routing apps to the detached USB output (AOSP `UsbAlsaManager` deregisters USB audio only on a physical detach); the Samsung HAL then skips writes and the playback capture gets about 2 silence gaps per second. Fix (`RelayService` start): transient audio focus pauses the target, MediaMute, then `SpeakerAnchor` (silent media track preferring the built-in speaker) moves the shared media output to the speaker while it is the only client, wait 3.5 s, claim the DAC, start capture, abandon focus. With this order the system DAC also works again right after Stop. The developer option `usb_audio_automatic_routing_disabled` works too but was rejected by the user.
- Kernel driver re-bind (`USBDEVFS_CONNECT`) returns EBUSY on this phone because the kernel already re-bound snd-usb-audio after the release (card nodes present); benign.
- Diagnostics: `USB rates:` log every 5 s; test tone `adb shell am start -n com.bitperfect.relay/.MainActivity --ei debug_tone_rate 48000 --ei debug_tone_sec 30`; `setprop debug.bitperfect.nofb 1` (nominal sizing), `setprop debug.bitperfect.alt N` (force alt).

## Device findings (Galaxy S25 FE SM-S731N, Android 17, FiiO BTR17, 2026-10-24)
- BTR17: VID 0x2972 PID 0x0088, UAC2, high speed. Clock source 0x29 behind selector 0x28; input terminal 2 goes straight to output terminal 0x14, so no Feature Unit and no hardware volume (`docs/ref/volume.md`). Playback alts 1 (32-bit in 4 B), 2 (16-bit in 2 B), 3 (raw data, DSD); data ep 0x01 async, wMaxPacketSize 200 (alt 1), bInterval 1; feedback ep 0x81 (4 B, bInterval 4) reports raw 0 every time, so packets are sized at the nominal rate. Full descriptor hex is in the diagnostics popup.
- The test tone now runs through `RelayService` (focus, MediaMute, speaker anchor, claim, stop, re-bind). 30 s at 44.1 kHz: underruns 0, err 0; afterwards `debug_sys_play_sec` (an AudioTrack pinned to the USB output) played on the BTR17 with 0 underruns, without a re-plug.
- Queue depth vs the system's USB audio path: a Bluetooth headset was the active media output during these tests. Apple Music's track then went through a duplicating output to A2DP and to the primary output, which the audio policy still routes to `AUDIO_DEVICE_OUT_USB_HEADSET card=1` (dumpsys media.audio_policy / media.audio_flinger) although we own the DAC. When the music app resumed after our transient focus, the host stopped completing our iso transfers for good (watchdog: completions 6, all transfers still queued) with 50 ms and 100 ms in flight, also as 25 transfers of 32 packets; 32 ms, 24 ms and 16 ms kept streaming. The same stall reproduced with the test tone while Apple Music played, and never without it. Cause below the app (kernel, xHCI or vendor offload) not visible without root. [AI judgment] Default 32 ms; the stall watchdog's recovery could not be tested (the DAC disconnected, see below).
- Relay Apple Music -> BTR17 with 32 ms in flight: at least 45 s of `USB rates` lines with underruns 0, err 0, short 0, dropped 0, across pause, resume and track skip of Apple Music. The 60 s mark and the stop line of that run were not captured.
- At 10:59:20, when that relay was stopped from the UI, the BTR17 disappeared from USB (`DEVICE_STATE_UNAVAILABLE`). User: the BTR17 had powered itself off (idle auto-off, battery empty), not our stop. With an empty battery it only enumerates after switching it to phone-powered mode.
- Re-test without a phone Bluetooth headset (main conversation, 2026-10-24): 100 ms in flight (10 x 81 packets) streams clean. Apple Music target 3 min and TIDAL relay: underruns 0, err 0, short 0, stallRestarts 0, ring steady near 230 ms; stops re-bind and the DAC stays on USB. The user heard TIDAL clean, and the 44.1 kHz 1 kHz test tone steady (it wavered with the old 16 ms queue and the old tone start). The stall above therefore needs a phone BT headset; default set to 100 ms [AI judgment].
- adb debug intents reach a running activity only with `am start --activity-single-top ...` (otherwise the task is just brought to front and the extras are dropped). Extras are handled in `onCreate` and `onNewIntent`. Props: `debug.bitperfect.inflight_ms N` (2..500), `debug.bitperfect.max_ppt N`.

## Device findings (Galaxy S25 FE, BTR17, TIDAL, 2026-10-25: system sounds and start leak)
- Why the speaker pin breaks: AOSP `getNewOutputDevices` honors an explicit route (our anchor's preferred speaker) only while no default-routed client is active on that output. The pin works because the capture start (its remote-submix device connects, `setDeviceConnectionStateInt`) re-evaluates every output while the target is paused. Afterwards any re-evaluation with the target playing picks USB. A system sound (lock sound, key click) plays on the `fast` output on the USB route; when it stops, AOSP `stopSource` re-evaluates the other active outputs of the same module, so the media (`deep_buffer`) output moves to the dead USB route and the capture gets silence gaps (`zeroGaps` +12 per 5 s, "half the music") until a restart. Seen in `APM_AudioPolicyManager` verbose logs (`logcat`), no root needed.
- On the dead route the target's pause never completes in the policy (no `stopOutput` after the focus loss, checked for 1 to 4 s), so pausing it and restarting the capture does not re-pin. Re-pin that works (`RelayService.repinMediaOutput`): transient focus, give the DAC back (route works, the pause completes), wait 3.5 s, claim again, restart the capture, give focus back. Verified once: lock sound -> "media output left the speaker" -> one re-pin -> route back on the speaker, `zeroGaps` stays flat. Cost: about 5 s of silence per event, after the sounds stop (2 s debounce, extended by every playback change).
- Muting or zeroing STREAM_SYSTEM does not stop the lock sound from starting the output (shell test), so it is no way to avoid the trigger.
- Start leak: the old order paused the target before muting. Now MediaMute engages 150 ms before the focus request (target muted on its live USB track, then paused). Still seen: on resume (focus given back) the AudioService player log shows the target `muted updated source:none` for about 13 ms before `portVolume`, on the speaker output. Two later starts showed 0 ms and 3 ms, and the user heard no leak on either (listening test 2026-10-25).
- Crash found while testing: the activity can be destroyed and recreated while the relay streams. `dllib_init` cleared the libusb table and reloaded it, and the libusb event thread called a null pointer (SIGSEGV in `usbi_handle_transfer_completion`). Fixed: `dllib_init` keeps an already bound library; `engine_apply_lib` does nothing (and does not stop the stream) when the same path is already loaded. The sticky service restart after such a crash then crashed again (typed `startForeground` without projection); a null-intent restart now undoes the mute and stops, and the start returns START_NOT_STICKY.
