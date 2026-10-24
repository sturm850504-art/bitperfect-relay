# Working Rules (owner: Claude)

Since 2026-10-11 Claude owns this project end to end. Earlier work (2026-10-05 to 2026-10-11) was done by a local Qwen model running inside Claude Code. Its rules, settings and progress log are in `docs/archive/qwen-era/`. They are history, not instructions.

## 1. Goals and quality bar
- Goal: finish the Android app per `docs/SPEC.md`. Declared complete by the user on 2026-10-24; later work is bug fixing from the user's reports.
- The Qwen and Gemini evaluation (former Goal B) was dropped by the user on 2026-10-24: all coding is done by Claude. Its files were deleted (git history keeps them).
- Quality bar (user, 2026-10-11): the defined usage flow works without bugs. Odd or abusive usage is out of scope unless it breaks the defined flow. Spend tokens accordingly: pick the cheapest check that proves the point.

## 2. Roles
- Claude: design, code, review, debugging, test plans, environment setup, docs, commits, and every decision these rules leave to Claude.
- User: product decisions, real-hardware tests (USB DAC, BT).

## 3. Trust baseline for Qwen-era work
- All Qwen-era claims are unverified: commit messages, the archived PROGRESS, test reports, "verified" and "do not re-verify" notes. Re-check a claim before relying on it.
- The code is the source of truth.

## 4. Chat and reports
- Korean, polite form (존댓말). The user listens through a screen reader: short sentences, no tables, no diagrams, few symbols.
- Never present the unverified as verified.
- Report order: what was done, what was verified and how, what is not verified, next step or question.
- Real-device test instructions: materials, numbered steps with copy-paste commands, expected result, what to report back.
- Questions: collect everything the user must decide into one message, each with a recommended option and the reason.
- Device test instructions start with the assumed state (app open, screen unlocked, DAC powered). The first line of a reply is a Korean report, never a working note. (2026-10-24 AI tuning)

## 5. Language
- Chat with the user: Korean.
- Markdown docs and new code (including comments): English. Existing code stays as is.
- App UI strings: Korean, in `strings.xml`.

## 6. Product core rules (user, top priority)
- Music-listening app: capture (other apps' audio) + relay (USB DAC / BT). "Bit-perfect" is a symbolic name: no processing on the relay path after capture.
- Reject original-sound leakage. Do not frame "small leakage is acceptable" as design. The capture's inherent original output on the phone's default output is a non-goal due to stock API limits (track volume 0 means capture 0). State it, do not hide it.
- No bit-perfect-breaking processing (resampling, volume, dither, mixing, noise) on the relay path. Exception defined by SPEC: BT resamples to the A2DP codec rate. If anything else seems unavoidable, stop and report pros, cons and a recommendation, then wait for the user.
- Decision 2026-10-09: capture is the method for all target apps. The source-based switch (2nd revision) is discarded. The reject-leakage principle stays.
- Solutions must work on a stock phone for an ordinary user. Developer options, rooting, vendor-only settings and other apps' binaries are not solutions. Without a public API, put vendor-specific fallbacks behind the official path. (2026-10-11: 13 turns on a developer-option workaround the user rejected.)

## 7. Decisions
- Decide small choices yourself. Tag them `[AI judgment]` in docs with a one-line reason. This includes new libraries (also add them to the settings-screen library catalog), new permissions, package name / minSdk / targetSdk / ABI changes, and gaps or contradictions in SPEC (choose what best serves bit-perfect relay and direct APK distribution).
- Ask the user only for product direction, bit-perfect trade-offs, and actions that need hardware or the user's accounts. Ask at design time, batched.
- Do not wait for step-by-step confirmation. Missing hardware test results do not block other work.

## 8. Fact checking
- Do not guess Android API, NDK, USB Audio Class, libusb, Oboe or libsoxr behavior, or library / Gradle versions. Check official docs, specs, source repos, or the local SDK stub jar (`javap`). Cite the source in docs and reports.
- Web access: WebSearch / WebFetch, or the built-in browser if those are unavailable.
- If docs are not enough, plan a VM or real-device test and decide from the result.
- Read a file before modifying it.

## 9. Code
- Kotlin official conventions. C++17.
- Log tag `BitPerfect`.
- Keep the C ABI between `cpp/engine` and Kotlin stable. State the reason when changing it.
- UI strings in `strings.xml` (Korean), never hardcoded.
- TalkBack description on every button, icon and list item. Never convey meaning by icon alone. Touch targets 48dp or more.
- Comments: short, English, explaining why.
- Unit tests for audio processing (format conversion, packing, ring buffer, descriptor parsing).
- No error hiding: no empty catch, no forced success values, no disabling tests to pass a build.

## 10. Build, test, debug
- After code changes: `./gradlew assembleDebug`, plus `./gradlew testDebugUnitTest` when unit tests exist (Git Bash; in PowerShell use `.\gradlew.bat`, a bare `gradlew.bat` is not found). A unit is done only when the build and its relevant check pass.
- Verification ladder, cheapest first: build and static checks, unit tests, VM (emulator) run, real device. Real device only for what the VM cannot do (USB DAC, BT A2DP, real capture behavior).
- VM tests: Claude runs them directly.
- Debugging: reproduce first, find the root cause, fix, re-run the same reproduction. A fix not re-run is reported as unverified.
- Same build error three times: stop and report the error and the attempts.
- Logs: read only the relevant part (errors, filtered logcat).
- Claude performs device actions over adb itself: USB permission dialog, app launch, target-app pick, settings toggles, `adb tcpip`, brightness. The user is asked only to plug, unplug, unlock (screen on before a test) or listen. Setup steps: `docs/DEVICE-SETUP.md`. (User consent 2026-10-24.)
- Before a listening request, confirm from logs that frames are flowing to the DAC. When sound is weak or absent, ask about the DAC knob and permission state first.
- Long device runs (subagent or background) re-check `adb devices`, battery and screen every 15 minutes; if the device disappears, mark the run "environment lost" and wait instead of concluding.

## 11. Progress and commits
- `docs/PROGRESS.md`: prepend one short entry per work unit: what changed (files), verified, not verified, next. The next session must be able to continue from the latest entry alone.
- Commit after each verified unit without asking. English message. Only Claude commits.

## 12. Context
- No fixed context cap for Claude. Still: read only needed ranges, do not re-read unchanged files, keep one theme per session.

## 13. Boundaries
- Do not touch files outside the project folder, except Claude's own memory and `~/.claude`.
- Do not change the archived files in `docs/archive/` except to add notes.

## 14. Reference material
- How UAPP works: `docs/private/README.md` (local only, not published). For "how does UAPP do it", read there before the web. Subagent reports and status lines name where the material came from.
