#!/bin/bash
# After a reinstall or re-plug: open the app, tap the "사용" switch (it toggles, so only when it is off) and accept the USB permission dialog.
A=/c/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe
dump() { MSYS_NO_PATHCONV=1 $A shell uiautomator dump /sdcard/ui.xml >/dev/null; MSYS_NO_PATHCONV=1 $A shell cat /sdcard/ui.xml; }
center() { grep -oE "$1[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' '; }
$A shell am start -n com.bitperfect.relay/.MainActivity >/dev/null 2>&1; sleep 2
set -- $(dump | center 'content-desc="사용"')
if [ -z "$1" ]; then echo "no switch"; exit 1; fi
$A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2
set -- $(dump | center 'text="확인"')
if [ -z "$1" ]; then echo "no dialog (permission already granted?)"; exit 0; fi
$A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1
echo "granted"
