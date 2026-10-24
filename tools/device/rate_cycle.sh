#!/bin/bash
# Change the relay rate from the relay card's dropdown for each rate given; print the log lines.
A=/c/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe
dump() { MSYS_NO_PATHCONV=1 $A shell uiautomator dump /sdcard/ui.xml >/dev/null; MSYS_NO_PATHCONV=1 $A shell cat /sdcard/ui.xml; }
center() { grep -oE "$1[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' '; }
$A logcat -c
for r in "$@"; do
  B=$(dump | center 'content-desc="샘플레이트 [^"]*"')
  set -- $B; $A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1.5
  B=$(dump | center "text=\"$r\"")
  set -- $B; $A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 8
done
$A logcat -d -s BitPerfect | grep -E "rate change|USB rates|clock rate|fail|error" | sed 's/ raw=.*zeroGaps/ ... zeroGaps/'
