# Device setup for real-device tests

The USB DAC takes the phone's only USB-C port, so adb runs over Wi-Fi during DAC tests. Claude does every adb step (RULES §10, user consent 2026-10-24). The user only plugs, unplugs, unlocks and listens.

Phone: Samsung S25 FE, adb serial `R5CY90H76VA`. adb: `C:\Users\odinn\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

## Before a test (user)
- Turn the screen on and unlock it. Claude cannot unlock the phone (security feature). That is the only thing asked before a test.

## Steps
1. User: connect the phone to the PC with the USB cable. If the phone asks "USB 디버깅을 허용하시겠습니까?", tick "이 컴퓨터에서 항상 허용" and allow (2026-10-24: without the tick the prompt came back).
2. Claude: `adb -s R5CY90H76VA tcpip 5555` over USB, then `adb connect <phone Wi-Fi IP>:5555` (192.168.0.25 on 2026-10-11; check the current IP).
3. Claude: confirm the Wi-Fi device in `adb devices`.
4. User: unplug the USB cable from the phone.
5. User: plug in the DAC.
6. Claude: everything else over adb (app launch, USB permission dialog, target-app pick, settings, screen-off timeout, brightness).

## Notes
- A reboot resets TCP mode (2026-10-24: after a system update the phone was reachable over neither USB nor Wi-Fi). Repeat from step 1. An update can also switch off "USB 디버깅" in Developer options; the user turns it back on.
- The "무선 디버깅" (Wireless debugging) toggle is not needed. It needs a one-time pairing with the PC and did not connect by itself (2026-10-24). `adb tcpip` over USB is the path.
- Screen off: during a test Claude raises the screen-off timeout (30 minutes on 2026-10-24), records the old value and restores it afterwards.
