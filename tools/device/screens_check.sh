#!/bin/bash
# Walk the non-relay screens: app picker, diagnostics popup, settings screen. Prints what each shows.
A=/c/Users/odinn/AppData/Local/Android/Sdk/platform-tools/adb.exe
dump() { MSYS_NO_PATHCONV=1 $A shell uiautomator dump /sdcard/ui.xml >/dev/null; MSYS_NO_PATHCONV=1 $A shell cat /sdcard/ui.xml; }
tapdesc() {
  B=$(dump | grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  if [ -z "$B" ]; then echo "no element: $1"; return 1; fi
  set -- $B; $A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
texts() { dump | grep -oE 'text="[^"]+"' | head -${1:-10} | tr '\n' ' '; echo; }

echo "== app picker"; tapdesc "앱 선택" && sleep 2 && texts 6; $A shell input keyevent BACK; sleep 1
$A shell input swipe 540 1700 540 700 300; sleep 1
echo "== diagnostics"; tapdesc "진단 정보" && sleep 2 && texts 8; $A shell input keyevent BACK; sleep 1
echo "== settings"; tapdesc "설정" && sleep 2 && texts 14; $A shell input keyevent BACK; sleep 1
echo "== back home"; texts 3
echo "crashes: $($A logcat -d -b crash | grep -c FATAL)"
