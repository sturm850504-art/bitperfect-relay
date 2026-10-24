# Target apps: can they be captured? (device checks)

How to check an app (no relay needed): play something in it, then `adb shell dumpsys media.audio_policy` and read the `Attributes: { ... Flags: 0x... }` line of its AudioTrack client (match by uid from `pm list packages -U`). Bit `0x400` is `AUDIO_FLAG_NO_MEDIA_PROJECTION` (AOSP `system/media/audio/include/system/audio-hal-enums.h`): MediaProjection playback capture is refused for that track. The public `dumpsys audio` player list shows `flags=0x0` for the same track, and the manifest flag `ALLOW_AUDIO_PLAYBACK_CAPTURE` can still be set, so neither of those is proof.

Results on Galaxy S25 FE (SM-S731N), Android 16, 2026-10-24:
- TIDAL (`com.aspiro.tidal`): capturable; relay plays (user listening, 2026-10-11).
- Apple Music (`com.apple.android.music`, versionCode 1599): track flags `0xa00` (deep buffer, mute haptic), capturable; relay plays (user listening).
- Spotify (`com.spotify.music`, versionCode 147346334): track flags `0x8600` includes `0x400`; capture blocked at runtime (AAudio stream). Manifest still says ALLOW_AUDIO_PLAYBACK_CAPTURE. Relay starts but the DAC gets silence (user listening). No workaround for a normal app.

Consent: since 2026-10-24 the app requests whole-display consent (`MediaProjectionConfig.createConfigForDefaultDisplay()`, API 34+), so the dialog is one "화면 공유" tap without an app picker. Audio is still matched by UID. No API pre-selects a given app.
