# Working Rules

Reports (chat) in Korean: short sentences + lists, no tables/diagrams.
The user only reads reports and real-device test results, not the code. Never present the unverified as verified.

## Language
- Chat with the user: Korean.
- All Markdown docs and newly written code (including comments): English.
- Existing code is left as is (do not rewrite it just for language).
- App UI strings stay Korean in `strings.xml`.

## Core rules (user — top priority)
- This is a music-listening app: capture (other apps' audio) + relay (USB DAC / BT). "Bit-perfect" is a symbolic name — it targets no processing on the relay path (after capture).
- Reject original-sound leakage. Do not dress up "small leakage is acceptable" as a design. The capture's inherent original output (also playing on the phone's default output) is a **non-goal** due to stock API limits (track volume 0 ⇒ capture 0); do not hide this.
- Do not put bit-perfect-breaking processing (resampling, volume, dither, mixing, noise) on the relay path (after capture).
  If it seems unavoidable, stop and report a pros/cons analysis + a #1 recommendation, then wait for the user's decision.
- Decision 2026-10-09: capture = the method (all target apps); "bit-perfect" = unprocessed relay (symbolic). The source-based switch (2nd revision) is **discarded**. The "reject leakage" principle is **kept** (capture's inherent leakage is stated as a non-goal).

## Working style (full delegation)
- Analyze options, pick the best one, and go straight to the conclusion. Reports contain only "choice + rationale".
- Ask only when options are truly 50:50 and block progress; include the #1 recommendation + rationale in the question.
- Conflict resolution: minimize user intervention, maximize autonomous handling, keep logical consistency.
- My judgments beyond the user's instructions get an `[AI judgment]` tag in reports/docs.
- Do not wait for step-by-step confirmation. Build success + PROGRESS update + report, then go straight to the next step. Stop only when the user explicitly stops me.
- Missing real-device test responses do not block progress; batch-integrate later.

## Test process (role split, 2026-10-10 — user directive)
- Testing is executed by **Gemini 3.1 Pro (Antigravity)**, not by Claude.
  - Claude: management, design, coding, review, test-plan authorship, VM/environment setup, emulator-boot troubleshooting, evidence review.
  - Gemini: executes an approved test plan **verbatim** and writes the run report. Nothing else.
- All features that do NOT require a real device are validated on the **Android VM (emulator)** first.
  Real-device testing is reserved for features that cannot be VM-tested (USB DAC relay, BT A2DP).
- Gemini's scope and permissions are limited and documented: see `docs/TEST-PROTOCOL.md`
  (allowlist: build, emulator/adb control, writes only in the run folder, report).
  Prohibitions: any file edit outside the run folder, any git command, step reordering/skips,
  retries beyond plan allowance, PASS claims without an evidence file.
- Every test plan (`docs/testplans/`) must be step-by-step: exact commands, expected output,
  named evidence files, per-step checklist, explicit on-failure behavior (usually ABORT).
- Test evidence is mandatory per step: full logs (no pipes), named screenshots, named
  `uiautomator` XML dumps, in `docs/reports/<YYYY-MM-DD_HHMM>_<plan-id>/` with `00_meta.txt`
  first and `99_meta.txt` last.
- Claude reviews the **evidence files**, never the executor's summary alone.
- Gemini is known to under-report or fabricate completed steps; the protocol's
  evidence-and-checklist rules exist to make fabrication detectable. Never loosen them.

## Fact checking
- Do not guess Android API, NDK, USB Audio Class, libusb, Oboe, libsoxr behavior.
  Verify against official docs / source repos and cite reference URLs in reports.
- Do not rely on memory for library / Gradle plugin versions or API names; verify.
- If docs are not enough, instruct a real-device test and proceed with the result.
- Read a file before modifying it. Do not guess-edit unread files.
- Do not implement unverified fixes as if they were verified.

## Autonomous decision areas (proceed without checking; `[AI judgment]` tag + rationale required)
- Adding new libraries / dependencies (also add to the settings screen library list data)
- Adding new permissions
- Changing package name, minSdk, targetSdk, supported ABIs
- Design decisions not in SPEC
- When SPEC is ambiguous / contradictory: pick the option best matching the user's goals (bit-perfect relay, stock APK distribution) and proceed

## Prohibited
- Do not touch files outside the project folder.
- Do not delete/disable tests to pass a build, and do not add error-hiding code (empty catch, forced success values).

## Code
- Kotlin official conventions. C++17.
- Unified log tag `BitPerfect`.
- Keep the C ABI between the engine (`cpp/engine`) and Kotlin stable. Report the reason when changing it.
- UI screen strings go in `strings.xml` (Korean). Do not hardcode them in code.
- TalkBack description on every button/icon/list item. Do not convey meaning by icon alone. Touch target 48dp+.
- Comments: short, in English, explaining why.
- Write unit tests for audio processing (format conversion, packing, ring buffer).

## Flow
- Follow SPEC "development stages" one at a time. Even within a stage, split into ≤5 files.
- Start with ≤3 lines: current stage, what to do now, files to touch.
- For big work, briefly write the plan (files, classes, functions, order) before coding.
- After changes, always build: `gradlew.bat assembleDebug`. Plus unit tests if present.
- Build failure: read the error and fix. Same error 3 times → stop and report the error + attempts.
- If the build didn't succeed, do not report "done".
- For build logs / logcat, read only the error part, not the whole thing.

## Progress log
- Append to `docs/PROGRESS.md` at the end of each work unit. Never delete old content; compress old details to one-liners.
- Format: current stage / done (with files) / remaining / unverified · pending questions / real-device test results.
- The next session must be able to continue from this file alone.
- After stage completion (build success + PROGRESS update), commit without asking. Message in English, include stage number.

## Context (80k, avoid compaction)
- Compaction (auto-summarization) is a failure signal, not a fallback. Goal: finish without compaction.
- Keep every task within 80k. If it looks bigger, split the task itself and start a new chat.
- One stage per chat session; start the next stage in a new chat.
- At the start of a new session, read only the latest PROGRESS.md entry and work immediately. Do not re-read files already read.
- For long files (build logs, big sources), read only the needed range.
- Near the limit: close the current unit → update PROGRESS + report → end the session.
- Work touching >5 files or multiple builds gets split before starting.
- Wide searches / investigation go to subagents; the main chat only receives conclusions.

## Report order
1. What was done
2. What was verified (build/test results)
3. What was not verified (honestly)
4. Real-device test instructions:
   - Materials (USB DAC, OTG adapter, BT earphones, etc.)
   - Numbered steps (commands copy-paste ready, e.g. `adb logcat -s BitPerfect:*`)
   - Expected results (what sound / what to see = success)
   - What the user should report (did sound come out, which log line)
5. Next step or question