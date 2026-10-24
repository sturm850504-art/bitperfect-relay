# Volume during a relay (read when working on volume or volume keys)

Added 2026-10-24 (task `docs/tasks/2026-10-24_driver-volume-diag.md`).

## Rules (user, 2026-10-24)
- Hardware volume first: the DAC's own Feature Unit, set with USB control requests. Samples stay untouched (bit-perfect).
- Digital volume only for DACs without a Feature Unit volume, off by default, labeled "비트퍼펙트 아님" in the UI. At 100% (or off) the samples pass bit-exact (unit test `PcmPackerTest.unityGainIsBitExact`).
- No original-sound leakage: any method that unmutes the music stream, even for a few ms, is not allowed.

## Hardware volume (USB Feature Unit)
- Parsing (`UacDescriptors`): AC unit layouts from UAC1 4.3.2 and UAC2 4.7.2; Feature Unit controls as in Linux `sound/usb/mixer.c` `parse_audio_feature_unit` (UAC1: `bControlSize`, channels = (bLength - 7) / size - 1, bit 0 mute, bit 1 volume; UAC2: 4 bytes per channel, channels = (bLength - 6) / 4 - 1, 2 bits per control, readable = bit 0, writable = bit 1, per `uac_v2v3_control_is_readable/_writeable` in `include/linux/usb/audio-v2.h`).
- Path: from each output terminal back through `bSourceID` / `baSourceID` to the playback alt setting's `bTerminalLink`; the Feature Unit with a host-writable volume closest to the output terminal is used. Volume on the master channel if it has it, else on every channel that has it.
- Requests (`UacVolume`, Linux `get_ctl_value_v1/_v2`, `snd_usb_mixer_set_ctl_value`, `include/uapi/linux/usb/audio.h`): class interface request, wValue = selector << 8 | channel, wIndex = unit << 8 | AC interface. UAC1 GET_CUR 0x81, GET_MIN 0x82, GET_MAX 0x83, GET_RES 0x84, SET_CUR 0x01. UAC2 CUR 0x01, RANGE 0x02 (only the first sub-range is read, like Linux). Volume is signed 16-bit in 1/256 dB; mute is 1 byte.
- Level per DAC (VID:PID) in app settings. First start with a DAC: its current level is read and kept (never jumps to max). Later starts restore the saved level. After every SET the level is read back and logged (`USB volume: set ... read back ...`).
- [AI judgment] One +/- press or volume key = 2 dB, rounded to the DAC's resolution.
- FiiO BTR17 (VID 0x2972 PID 0x0088): no Feature Unit. Its AC topology is input terminal 2 (USB streaming) straight to output terminal 0x14 (speaker type). Volume is only on the device's own buttons, so the app offers digital volume for it.
- FiiO Air Link (VID 0x2972 PID 0x0158, UAC1 BT transmitter, 16/24-bit up to 96 kHz): Feature Unit 8, master channel, range -50.0 to 0 dB, resolution 1 dB; the app shows 25 steps. Device test 2026-10-24: volume keys, slider tap, +/- buttons and notification -/+ all SET and read back the same value.
- Restore after stop: the music index is per device, and the device used for the index is the one an active stream plays on, not the strategy route. After the speaker anchor stops, the target app's muted track can stay on the speaker for about 100 ms while `getAudioDevicesForAttributes` already reports USB; restoring then wrote the saved index to the speaker and left USB at the muted value (Air Link run, 2026-10-24). `RelayService.awaitMediaRouteOffSpeaker` therefore also waits until no active playback configuration is on the built-in speaker.

## Digital volume
- Settings switch "디지털 볼륨 (비트퍼펙트 아님)", enabled only while the connected DAC has no hardware volume; applies from the next start.
- Applied in `PcmPacker` on the integer sample: (x * gain + 0.5) >> 16, gain Q16.16. Unity gain skips the multiply.
- [AI judgment] Percent to gain is a square taper ((p/100)^2), closer to perceived loudness than linear. Step 5%.

## UI
- Main screen while relaying: volume card with dB (hardware) or percent plus "비트퍼펙트 아님" (digital), a slider and -/+ buttons (48 dp, TalkBack labels with the value). DAC without hardware volume and digital off: the card says so and points to the setting.
- Relay notification: 볼륨 내리기 / 볼륨 올리기 actions while a volume control is attached (not checked on the device yet).

## Volume keys: remote-playback media session
- `RelayService` owns a `MediaSession` with `setPlaybackToRemote(VolumeProvider)` (absolute, steps = range / step) and PLAYING state while relaying.
- Why it gets the keys (AOSP frameworks/base main, fetched 2026-10-24): `MediaSessionService.dispatchVolumeKeyEventLocked` -> `dispatchAdjustVolumeLocked` sends the key to `MediaSessionStack.getDefaultVolumeSession()`, the first session in priority order whose playback state is active and that `canHandleVolumeKey()` (remote sessions qualify unless VOLUME_CONTROL_FIXED). Only when there is none does the key adjust the suggested stream (music), which would unmute it.
- `MediaSession` forwards volume to the `VolumeProvider` only when a `Callback` is set (`MediaSession.postToCallbackDelayed`: nothing happens if `mCallback == null`), so the session sets an empty callback.
- Priority: `MediaSessionRecord.setPlaybackState` pushes a session to the top when it enters SKIPPING_TO_NEXT/PREVIOUS, FAST_FORWARDING or REWINDING, or enters BUFFERING/CONNECTING/PLAYING from another state (`ALWAYS_PRIORITY_STATES`, `TRANSITION_PRIORITY_STATES`). A music app that skips a track therefore takes the top and its local session would get the keys. [AI judgment] Our session re-takes the top every second with PAUSED -> PLAYING.
- Media buttons (play/pause/next) are not affected: the media button session is chosen from the app that played audio last (`MediaSessionStack.updateMediaButtonSessionIfNeeded`), which is the music app.

### Device result (Galaxy S25 FE SM-S731N, Android 17 / API 37, 2026-10-24)
- Setup: Apple Music playing, BT headset active as media output, music stream muted by MediaMute, our session active. Keys injected with `adb shell input keyevent`. Done with the debug harness `--ei debug_volume_keys_sec N` (session + MediaMute without USB: the DAC had disconnected, see usb-dac.md).
- Without the priority re-assert: screen on, keys reached our session (`volume session: adjust 1/-1`) and the music stream stayed muted. After a track skip (Apple Music took the top), VOLUME_UP went to the music stream (`adjustSuggestedStreamVolume ... from android uid 1000`) and unmuted it until MediaMute re-muted 0.7 s later. With the screen off and Apple Music on top, Samsung SystemUI adjusted the music stream directly (`from com.android.systemui`), a raise then lower unmuted it and it stayed unmuted (MediaMute only watched Settings, which an unmute without index change never touches).
- With the re-assert and the MediaMute fix: 6 of 6 keys reached our session (screen on, 2 s after a skip, 0.3 s after a skip, screen off after a skip). The AudioService event log for that minute shows only our own mute at start and restore at stop: no key reached the music stream, no unmute. MEDIA_PAUSE / MEDIA_PLAY still controlled Apple Music.
- Not checked: real hardware keys (injected keys take the same PhoneWindowManager path), a long relay with many skips, other music apps, with the volume session attached to a real USB relay (code path is the same `RelayVolume`).

## MediaMute fix (leak found by the key test)
- Re-mute triggers now also include AudioService's `android.media.VOLUME_CHANGED_ACTION` and `android.media.STREAM_MUTE_CHANGED_ACTION` broadcasts (sent with `sendBroadcastToAll`, AOSP `AudioService.broadcastMuteSetting` and `VolumeStreamState`), so an unmute is caught at once.
- While engaged the check is mute only: `getStreamVolume` reports 0 for a muted stream and `setStreamVolume` unmutes (`STREAM_MUSIC unmuting by onSetStreamVolume` in the event log). A first version that also compared the index looped (mute, broadcast, set index, unmute) for the whole test; fixed before the final run.
- Inferred, not proven: volume changes from a plain background service were ignored on this phone. The debug harness runs without foreground status; one run ended with the screen off and its restore left no event in the AudioService log (stream stayed muted at index 2; restored by hand with `cmd media_session volume --stream 3 --set 15`). The relay runs as a foreground service, and its restores were applied in every relay test.
