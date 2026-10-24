# UI spec (read when working on UI/accessibility)

Behavior spec for the main screen. Visual layout: `docs/ref/ui-draft.md` (v2, the design target).
UI text lives in `strings.xml` (Korean); never hardcode screen strings.

## Card 1 — app selection
- **"+ app add"** button: opens a popup list of installed apps; choosing one **adds it to the app list**.
- **App list**: apps the user added. **Persisted across restarts** (DataStore).
- **App list item**: icon, name, **sample-rate dropdown** (only supported rates shown), trash icon at the far right.
  - The sample-rate dropdown is **the rate used to bring this app's sound**; **persisted per app**.
  - Trash: tapping shows a **delete-confirmation popup**; confirming **removes the app** from the list.
- **Selection (single)**:
  - No app selected → tapping an item selects it.
  - A different app is selected → tapping **switches** the selection to the tapped app.
  - Tapping the currently selected item → **deselects** (no app selected).
- Apps that block capture (`allowAudioPlaybackCapture=false`, etc.) show "capture blocked". If detection is uncertain, actually capture briefly and judge by silence.
  - Implemented 2026-10-24 (`capture/CaptureCheck.kt`): "캡처 차단" under the app name, in the app list and in the add-app popup; the TalkBack description includes it ("Chrome, 미선택, 캡처 차단").
  - Sources: the app's manifest `android:allowAudioPlaybackCapture` (default: allowed for targetSdk 29+, not allowed below; developer.android.com/media/platform/av-capture). The SDK has no public getter (`ApplicationInfo`, android-36 stub via javap), so the compiled manifest is read through `createPackageContext(...).assets.openXmlResourceParser(cookie, "AndroidManifest.xml")`, trying each cookie until the manifest's `package` matches (a cookie-less lookup returned Chrome's shared trichromelibrary manifest on the API 36 emulator). Plus a known runtime-blocker list: Spotify `com.spotify.music` (target-apps.md).
  - [AI judgment] A blocked app stays selectable: an app update may allow capture again. An unreadable manifest is not marked; the 5 s silence hint covers it while relaying. The "capture briefly" check is not implemented.

## Card 1 while relaying — relay card with visualizer (user, 2026-10-24)
- While relaying, card 1 shows "재생 중: <app>" with the rate button, a visualizer, and a footer line "<form> · <color> · 지연 약 N ms". The app list and the "앱 선택" button are hidden, so the app cannot be switched mid-relay (switching used to leak the original to the speaker before the stop). Stop brings the list back.
- Rate button while relaying: changes the running relay (capture reopened and DAC re-clocked; about 0.6 to 0.8 s of silence; mute and speaker anchor stay) and the app's stored rate.
- Visualizer (`ui/Visualizer.kt`, data from `service/RelayMeter`, a read-only copy of the relayed samples): horizontal swipe changes the form, vertical swipe the color preset; both are remembered. Forms in order: scrolling waveform (default, last 3 s, faint peak envelope with the average level solid on top, L top, R bottom), oscilloscope (20 ms, rising-zero trigger, a new trace every 50 ms with 6 fading traces as afterglow), spectrum (2048-point FFT, 40 log bars 30 Hz to 20 kHz, falling peak caps) [AI judgment, recommended], vectorscope with correlation bar (right = L and R alike, left only for out-of-phase sound) [AI judgment, recommended], analog VU meters L and R (fan scale, needle). Presets: 앰버 (default), 빈티지, 그린, 아이스 블루, 네온, 모노, and 사용자.
- Settings button "시각화 설정" (user, 2026-10-24): preset list, user colors (7 slots: 배경, 왼쪽, 오른쪽, 강조, 글자, 미터판, 바늘; hue, saturation, brightness sliders; edits apply at once and select 사용자), VU needle response time (0.2 to 1.5 s, default 0.6 s), view sync offset (-500 to +800 ms, default 0).
- VU needle: damped mass-spring model with zeta 0.8 (1.5% overshoot); omega set from the response time (13.1 rad/s gives the VU standard's 99% in 300 ms, IEC 60268-17, checked numerically). The user found 0.3 s too nervous for dense music, so the default is 0.6 s, and the drive is smoothed (60 ms) before the needle, like the meter's coil. Deflection proportional to voltage; end pins with a small bounce. [AI judgment] Automatic meter sensitivity: a slow level envelope (up 2 s, down 8 s, within -30..-6 dBFS) reads -2 VU, because a fixed -18 dBFS reference kept the needle pinned on loud pop (device, 2026-10-24).
- Sync: the view shows the audio when the DAC plays it (delayed by the relay's buffered audio, ring plus USB in flight) plus the user's offset for delays after the DAC (Bluetooth). Capture arrives in chunks of about 46 ms; block views read 80 ms behind and advance at the block rate, so they move smoothly.
- Delay figure: audio buffered in our pipe (ring plus USB transfers in flight). Android's own capture latency cannot be measured by the app and is not included.
- Accessibility: the canvas has a description (form, color, how to change) and custom actions (next form, previous form, next color).

## Card 2 — output device selection
Changed 2026-10-11 (user): the selection tree and Bluetooth are gone.
- **No USB DAC connected**: the card shows **"없음"** (none) only.
- **USB DAC connected** (auto-refresh on connect/disconnect): the card shows the DAC facts, and below them a **"사용" (use) switch**.
  - Switch on: asks for USB permission if needed; granted → the DAC is the output. Denied → stays off, status says so.
  - Switch off: no output. Locked while relaying (TalkBack: "사용 (재생 중에는 바꿀 수 없음)").
  - DAC facts: device **name**; **NO sample-rate dropdown** (the rate comes from the device); a **"UAC 1.0" / "UAC 2.0" badge** (display only, no function); a **power-state icon** (display only):
      - power-supplied (전원공급) → **bolt icon** ⚡
      - built-in battery (내장 배터리) → **bolt-forbidden icon** ⚡̸
    - Descriptor details in `docs/ref/usb-dac.md`.

## Output-rate info (shown while relaying)
- Shown **next to the output device name**:
  - **USB DAC**: the **same sample rate as the selected app**.
  - Implemented 2026-10-24: "<DAC name> · 44.1kHz" while relaying, also in the row's TalkBack description. Rates show as "44.1kHz" (the app rate button used to show 44100 as "44kHz").

## Bottom of screen
- Status line: idle / relaying (source → output, format) / error reason.
- Button to the settings screen.

## Relay state machine (start / stop / switch)
- **Start** — the relay starts **only when BOTH** hold:
  1. an app is selected in the app list, and
  2. an output device is selected (the DAC's "사용" switch is on). If either is missing, the status line says which.
  → then **request the required permission** from the user, and **start**.
- **Stop** — while running, stop immediately if **any** of these happens:
  - the selected app **changes** to a different app, or is **deselected**;
  - the output device is **deselected** (removed).
- **Output switch** — with one USB DAC output and the switch locked while relaying, there is no switch during a relay (2026-10-11). Turning the switch on does not start the relay; the start button does.
- **USB DAC physical disconnect → reconnect** — if the physical connection drops and is re-plugged (the user did **not** deselect it), the app **auto re-registers it as the output device** and **requests permission again**.
- **Permission denied** — if the user denies the request: **keep the selected app**, and **remove the output device only**.

## Background operation
- The relay keeps running in the **background** (foreground service, wakelock).

## Accessibility (required)
- TalkBack description on every button/icon/list item. Do not convey meaning by icon alone.
- Selection state read via `selected`/`stateDescription`. Example: "Apple Music, selected, 24-bit 96 kilohertz".
- Relay state changes are announced automatically by TalkBack (live region). Implemented 2026-10-24: the status line has `liveRegion = Polite`.
- Touch target 48dp+. Layout must not break when the system font size is enlarged.
  - 2026-10-24: fixed heights became minimum heights (buttons grow with the font); content stays inside the system bars (`safeDrawingPadding`, targetSdk 36 draws edge to edge and the title sat under the status bar). [AI judgment] Above font scale 1.3 an app row stacks: rate button and trash move under the name (at 2.0 the name wrapped one letter per line). Checked on the emulator at 1.3 and 2.0 (largest offered).

## Battery optimization (SPEC "First run")
- [AI judgment] Asked once, on the first Start tap (not at launch, and before the start checks so it does not depend on an app and DAC being ready): if `PowerManager.isIgnoringBatteryOptimizations` is false, the system request `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (manifest permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; android-36 stub via javap) opens; if no handler exists, the settings list `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. Whatever the answer, the start continues. "Asked" is stored in prefs (`battery_asked`) and never asked again. Direct APK distribution, so the Play policy on this permission does not apply.
