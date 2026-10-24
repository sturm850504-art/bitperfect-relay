#!/bin/bash
# Play TIDAL, open the app, tap Start, accept the capture consent. Prints the USB start line.
A=/c/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe
$A shell input keyevent MEDIA_PLAY; sleep 1
$A shell am start -n com.bitperfect.relay/.MainActivity >/dev/null 2>&1; sleep 3
MSYS_NO_PATHCONV=1 $A shell uiautomator dump /sdcard/ui.xml >/dev/null
B=$(MSYS_NO_PATHCONV=1 $A shell cat /sdcard/ui.xml | grep -oE 'content-desc="시작"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
set -- $B
if [ -z "$1" ]; then echo "no start button"; exit 1; fi
$A logcat -c
$A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3
MSYS_NO_PATHCONV=1 $A shell uiautomator dump /sdcard/ui.xml >/dev/null
MSYS_NO_PATHCONV=1 $A shell cat /sdcard/ui.xml | grep -q '화면 공유' && $A shell input tap 854 1643
sleep 13
$A logcat -d -s BitPerfect | grep -E "USB start|failed|error" | tail -2
