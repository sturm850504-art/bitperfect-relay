#!/bin/bash
# Open the app and tap 정지 if a relay is running.
A=/c/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe
$A shell am start -n com.bitperfect.relay/.MainActivity >/dev/null 2>&1; sleep 2
MSYS_NO_PATHCONV=1 $A shell uiautomator dump /sdcard/ui.xml >/dev/null
B=$(MSYS_NO_PATHCONV=1 $A shell cat /sdcard/ui.xml | grep -oE 'content-desc="정지"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
set -- $B
if [ -z "$1" ]; then echo "not relaying"; exit 0; fi
$A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 5
echo "stopped"
