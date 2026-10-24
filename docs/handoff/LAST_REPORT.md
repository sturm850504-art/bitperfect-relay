# Last report (2026-10-25)

## Done
- Bug-fix session for the user's two reports, both fixed and checked by the user's ears: start leak on the phone speaker (mute before pausing the target), half audio after system sounds (detect the route break, hold at once, re-pin by giving the DAC back and claiming it again; about 5 s of silence, user accepted).
- Also fixed: stop leak (target paused during the hand-back), crash when the activity is recreated while relaying, crash of the sticky service restart, saved level restored onto the speaker when the DAC vanished during a start.
- Version 0.1.1, signed APK in the PC Downloads folder; pushed to GitHub; release v0.1.1 published with the APK.
- Details: `docs/PROGRESS.md` (2026-10-25 entries), `docs/ref/usb-dac.md` (2026-10-25 findings).

## Open
- Not checked for leaks: DAC unplug while relaying, live rate change, notification sounds.
- The phone speaker's music level was raised from 4 to 9 by a test; the user was told to lower it.

## Next
- Leak checks for DAC unplug, rate change and notification sounds when the user wants them.
