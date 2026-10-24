# UI draft (visual reference, managed)

Visual design draft for the main screen. This file is the **re-verification source**: any UI work must match this draft (structure/labels/behavior) and the behavior spec in `docs/ref/ui-spec.md`. Update this file whenever the draft changes (bump the version log).

- **Reference image**: `docs/ref/ui_draft_v1.png`
- **Source**: AI-generated mockup provided by the owner (2026-10-10); the owner confirmed the *structure* matches the original plan. **App/device names shown are placeholders** (YouTube, Spotify, FiiO KA3, Galaxy Buds Pro are examples, not real selections).
- **Status**: v2 — approved as the design target; implementation in progress (current `MainScreen.kt` layout is discarded and being rebuilt to this draft).

## Global

- Dark Material 3 theme. Background `#121212` (charcoal), cards `#1E1E1E`, rounded corners.
- Screen title at top: **"오디오 장치 관리"** (Audio Device Management).
- White primary text, light-gray secondary text. High-contrast, legible controls.
- Two vertical cards: (1) 앱 선택 (App selection), (2) 출력장치 선택 (Output device selection). Below them: status line + settings entry (see `ui-spec.md`).
- The app must keep running in the **background** while relaying (foreground service).
- Touch targets ≥ 48dp; TalkBack labels on every control (see `ui-spec.md` accessibility).

## Card 1 — 앱 선택 (App selection)

- Card header row: title **"앱 선택"** on the left, **"+ 앱 추가"** (Add app) outlined button on the right (blue/gray border).
  - **+ 앱 추가**: tapping opens a **popup list of installed apps**; choosing one **adds it to the app list** below.
- **앱 리스트 (App list)**: vertical list of the apps the user has added. **The list is persisted** — it survives app restarts (stored in DataStore).
- **앱 리스트 아이템 (App list item)** — each row:
  - App brand icon (brand color) on the left, app name next to it.
  - **Sample-rate dropdown**: the rate used to bring this app's sound. **Only supported rates are shown** in the dropdown.
  - **Trash icon** at the far right: tapping shows a **delete-confirmation popup**; confirming **removes the app** from the list.
- **Selection behavior (single selection)**:
  - Tap an item while **no app is selected** → select that app.
  - Tap an item while **a different app is selected** → **switch** the selection to the tapped app.
  - Tap the **currently selected** item → **deselect** (no app selected).
- **Selection state**: the selected row has a **glowing blue border + blue tinted background**; unselected rows are muted gray.
- Per-app sample rate **persists across restarts**. Capture-blocked apps are flagged per `ui-spec.md`.

## Card 2 — 출력장치 선택 (Output device selection)

- Card title **"출력장치 선택"**.
- **출력장치 (Output device) row**: shows the **current output device** (e.g. "재생장치: USB DAC (FiiO KA3)"). When nothing is selected it shows **"없음"** (None). **Tapping this row removes the output device** (selection → "없음").
- **출력 장치 선택 트리 (Output device selection tree)** with expandable parents (`▼`):
  - **USB** — a USB DAC appears as a **leaf only when a USB DAC is connected** (auto-refresh on connect/disconnect).
    - **USB DAC leaf**: device **name**; **NO sample-rate dropdown** (the rate is taken from the device); a **"UAC 1.0" / "UAC 2.0" badge** (orange/gold pill, **display only, no function**); and a **power-state icon (no function)**:
      - **power-supplied (전원공급)** → **electric (bolt) icon** ⚡
      - **not power-supplied / built-in battery (내장 배터리)** → **electric-forbidden icon** ⚡̸
    - Details per `docs/ref/usb-dac.md`.
  - **블루투스 (Bluetooth)** — a BT music device appears as a **leaf when connected**. A **refresh icon** sits next to the name.
    - **Bluetooth leaf**: device **name** + **supported-codec dropdown**.

### Output-rate info (shown while outputting)

- While relaying, an **output-rate info** is shown **next to the output device name**:
  - **USB DAC**: show the **same sample rate as the selected app** (e.g. "48k").
  - **Bluetooth codec**: show **"(선택된 코덱)에 최적화중"** (optimizing for *(selected codec)*).

## Theme / palette

- Charcoal background `#121212`; card surface `#1E1E1E`; selection accent blue; badges orange/gold; white text; light-gray secondary text.
- Sharp, legible dropdown menus; `↳`-style indent for tree children; pill badges for UAC version / power state.

## Relay state machine (start / stop / switch)

- **Start condition** — the relay **starts only when BOTH** are satisfied:
  1. an app is selected in the app list, and
  2. an output device is selected.
  → When both are met, **request the required permission from the user**, then **start**.
- **Stop** — while running, stop immediately if **any** of these happens:
  - the selected app **changes** to a different app, or is **deselected**;
  - the output device is **deselected** (removed).
- **Output switch (not deselect)** — changing from one output device to another **does NOT stop** the relay; only the target device changes. (After a stop, if the app is still selected and only the device is re-selected, it **resumes/replays immediately**.)
- **USB DAC physical disconnect → reconnect** — if the DAC's physical connection drops and is re-plugged (i.e. the user did **not** deselect/change it), the app **auto re-registers it as the output device** and **requests permission** again.
- **Permission denied** — if the user denies the permission request: **keep the selected app**, and **remove the output device only**.

## Interaction summary

1. Add app (+ 앱 추가) → pick from installed apps → row appears with a sample-rate dropdown.
2. Select an app row (single selection; tap-selected deselects) → capture source chosen.
3. Connect a USB DAC or BT device → its leaf appears in the tree.
4. Select an output device row → relay target chosen; **both sides selected ⇒ permission requested ⇒ relay starts** (status line reflects it).
5. Changing the **app** or **removing the output** stops the relay immediately; **switching** the output device re-routes without stopping.

## Version log

| v | date | change |
|---|------|--------|
| 1 | 2026-10-10 | Initial draft from owner-provided AI mockup (`ui_draft_v1.png`); current MainScreen layout discarded. |
| 2 | 2026-10-10 | Folded in the owner's full feature description: app-list item actions (3-state selection, sample-rate-only dropdown, trash + confirm popup); USB DAC leaf = name + UAC 1.0/2.0 badge + power icon (bolt = power-supplied / bolt-forbidden = built-in battery), **no rate dropdown**; BT leaf = name + refresh icon + supported-codec dropdown; output row shows current device or "없음" and tap-removes; relay start/stop/switch state machine (start on both-selected → permission; stop on app change/deselect or output deselect; output switch → re-route, no stop; USB physical reconnect → auto re-register + permission; permission denied → keep app, remove output); output-rate info next to device name (USB = app rate / BT = "(codec)에 최적화중"); background operation. |
