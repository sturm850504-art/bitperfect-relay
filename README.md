# BitPerfect Relay

Relay the sound of your streaming app (TIDAL, Apple Music, ...) to a USB DAC on Android through
the app's own USB audio driver, untouched after capture.

## 한국어 요약

스트리밍 앱(TIDAL, Apple Music 등)이 내는 소리를 캡처해서, 안드로이드 오디오 시스템을 거치지 않고
앱이 직접 USB DAC로 보냅니다. 캡처한 뒤로는 리샘플링, 볼륨, 디더, 믹싱 같은 가공을 하지 않습니다.
중계하는 동안 폰 스피커로 원래 소리가 새지 않게 막고, DAC를 뽑으면 플레이어를 멈춘 뒤 볼륨을 돌려놓습니다.
재생 중에 샘플레이트를 바꿀 수 있고, 흐르는 파형, 오실로스코프, 스펙트럼, 벡터스코프, 아날로그 VU 미터로
지금 나가는 소리를 볼 수 있습니다.

## What it does

- Captures one chosen app with Android's playback capture (`AudioPlaybackCaptureConfiguration`,
  MediaProjection consent) at the sample rate you set for that app.
- Streams the captured PCM to the USB DAC with its own USB Audio Class 1/2 driver: libusb
  isochronous transfers with explicit feedback, the kernel driver detached while relaying.
  No resampling, volume, dither or mixing on the relay path.
- Keeps the original from leaking out of the phone speaker: the music stream is muted while relaying,
  and the shared media output is pinned to the (muted) speaker so the DAC belongs to the relay.
- Volume: the DAC's hardware volume (USB Feature Unit) from the app, the volume keys and the
  notification. Optional digital volume for DACs without one (off by default, marked not bit-perfect).
- Unplugging the DAC stops the player first and only then unmutes, so nothing leaks to the speaker.
- Change the sample rate while playing (about one second of silence while the DAC re-clocks).
- Visualizer while relaying, drawn at the moment the DAC plays the audio: scrolling waveform,
  oscilloscope, spectrum, vectorscope and analog VU meters with needle ballistics. Swipe sideways
  for the form, up and down for colors; custom colors, needle response time and a sync offset
  (for Bluetooth transmitters after the DAC) in the settings.

## Limits

- Only apps that allow playback capture can be relayed (Spotify, for example, blocks it).
- "Bit-perfect" here means the path after capture. Android mixes and may resample the app's sound
  before capture; set the app's rate to the stream's rate to avoid resampling.
- The phone keeps a silent copy of the original on its default output; stock Android cannot capture
  an app whose own output is turned off.

## Requirements

- Android 13 or later (minSdk 33), a phone with USB host support, a USB Audio Class 1 or 2 DAC.
- Tested on a Samsung Galaxy S25 FE with an iFi iDSD Valkyrie, a FiiO BTR17 (UAC2) and a
  FiiO Air Link (UAC1 Bluetooth transmitter with hardware volume).

## Build

```
./gradlew assembleRelease
```

The release build is signed only when `~/.android/bitperfect-relay/keystore.properties` exists
(or the file named by `BITPERFECT_SIGNING`); otherwise it is left unsigned. `./gradlew assembleDebug`
builds a debug APK. Unit tests: `./gradlew testDebugUnitTest`.

## License

MIT, see `LICENSE`. libusb (`app/src/main/cpp/usb/libusb`) is LGPL 2.1 and is built as a separate
shared library.

Not affiliated with USB Audio Player PRO, TIDAL, Apple Music or any DAC maker. Developed with
Claude Code.

## Inspiration

The idea for this app came from watching [Producer dk](https://www.youtube.com/@producerdk)'s
YouTube channel. The channel has nothing to do with this project.

이 앱은 [프로듀서 DK](https://www.youtube.com/@producerdk) 유튜브 채널을 즐겨 보다가 만들게 되었습니다.
채널과 이 프로젝트는 아무 관계가 없습니다.
