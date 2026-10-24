#!/bin/bash
# Add apps by label through the app picker: open the picker, scroll until the label shows, tap it.
A=/c/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe
dump() { MSYS_NO_PATHCONV=1 $A shell uiautomator dump /sdcard/ui.xml >/dev/null; MSYS_NO_PATHCONV=1 $A shell cat /sdcard/ui.xml; }
for label in "$@"; do
  $A shell input tap 540 435; sleep 2
  found=""
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    B=$(dump | grep -oE "content-desc=\"$label\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
    if [ -n "$B" ]; then set -- $B; $A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); found=1; break; fi
    $A shell input swipe 540 1500 540 900 300; sleep 1
  done
  [ -n "$found" ] && echo "added $label" || { echo "not found $label"; $A shell input keyevent BACK; }
  sleep 1.5
done
dump | grep -oE 'content-desc="[^"]*(선택됨|미선택)[^"]*"'
