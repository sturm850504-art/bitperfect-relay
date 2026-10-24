# Last report (2026-10-25, before clearing the conversation)

## Done
- App complete and published: https://github.com/sturm850504-art/bitperfect-relay, release v0.1.0 (signed, whole-app R8, 2.5 MB). Signed release build is installed on the phone. Details: latest entries of `docs/PROGRESS.md`.
- Local only: branch `local-full-history` (full old history, never push), `docs/private/` (UAPP notes, git-ignored). Repo git author is the GitHub noreply address.

## Now: bug fixing (user report, 2026-10-25, verbatim)
1. "처음에 스트리밍 앱에서 노래 재생 중인 데 비트퍼팩트 모드 켤 때 폰 스피커로 잠시 소리 새는 문제가 있어."
2. "비트퍼팩트 모드 켜고 노래 듣는중에 시스템 소리가 발생하면 초창기 때 발생했던 것처럼 소리가 절반만 들리고 절반은 끊기는 현상이 다시 켤때까지 계속 발생해. 확인된 건 화면 잠글 때 효과음 소리 발생할 때나 카톡으로 타이핑 할때 키보드 버튼 누르는 소리 때 였으니까 전반적으로 대부분의 소리 발생 시 공통적으로 발생하는걸로 추정하고 있어"

## Environment at handoff
- The user toggled developer options off and on. Wi-Fi adb was re-enabled by Claude (`adb tcpip 5555` over USB): `adb connect 192.168.0.25:5555` works. USB cable to the PC was still attached.
- The user was asked to unplug the PC cable and plug the FiiO BTR17 (with earphones) into the phone, then say "꽂았어". First step of the new conversation: `adb devices`, `dumpsys usb | grep product_name` to confirm the BTR17.
- Phone app state: release build (no debug extras; `run-as` does not work on release). Apps in the list: TIDAL (selected), Apple Music, Spotify. TIDAL rate 44.1 kHz.

## Plan (Claude's, not yet started)
- Reproduce over adb, judge from system records, not by ear: start TIDAL (`input keyevent MEDIA_PLAY`), start the relay (`tools/device/start_relay.sh`), poll `dumpsys audio` every ~100 ms during the start for TIDAL's AudioPlaybackConfiguration device id and `mutedState`, the STREAM_MUSIC mute state, and AudioService events. A player on the speaker device without a mute is the leak (bug 1).
- Bug 2: while relaying, trigger system sounds (screen lock with `input keyevent POWER`, key clicks) and watch our `USB rates` log (capture rate, zeroGaps, maxChunkGap, underruns) and `dumpsys media.audio_flinger` output threads and devices. Suspect: sonification is routed to the still-listed USB headset output, which disturbs the shared output's pace (the "half audio" of 2026-10-11, see SpeakerAnchor's comment).
- If debug extras or `run-as` are needed, install a debug build: it has a different signature, so it needs an uninstall (settings reset); prefer staying on the release build and logcat.
- Helper scripts: `tools/device/` (start_relay.sh, rate_cycle.sh, screens_check.sh, add_apps.sh).
