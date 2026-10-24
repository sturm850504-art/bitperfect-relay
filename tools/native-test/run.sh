#!/usr/bin/env bash
# Build the native unit tests with the NDK and run them on a connected emulator or device.
# Usage: tools/native-test/run.sh [adb serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1  # keep /data/local/tmp as is under Git Bash
cd "$(dirname "$0")/../.."
SDK="${ANDROID_SDK:-$LOCALAPPDATA/Android/Sdk}"
NDK="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)"
ADB="$SDK/platform-tools/adb.exe"
SERIAL=${1:-}
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
ABI=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
case "$ABI" in
  x86_64) TRIPLE=x86_64-linux-android31 ;;
  arm64-v8a) TRIPLE=aarch64-linux-android31 ;;
  *) echo "unsupported ABI $ABI"; exit 2 ;;
esac
OUT="build/native-test"; mkdir -p "$OUT"
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/$TRIPLE-clang++.cmd" -std=c++17 -O1 -Wall -Wextra \
  -static-libstdc++ -I app/src/main/cpp/usb app/src/test/cpp/usb_native_test.cpp -o "$OUT/usb_native_test"
$ADB push "$OUT/usb_native_test" /data/local/tmp/usb_native_test >/dev/null
$ADB shell chmod 755 /data/local/tmp/usb_native_test
$ADB shell /data/local/tmp/usb_native_test
