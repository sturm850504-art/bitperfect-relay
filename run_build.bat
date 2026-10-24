@echo off
cd /d "%~dp0"

echo === 1/3 Build (assembleDebug) ===
call gradlew.bat assembleDebug

echo.
echo === 2/3 Install ===
adb install -r app\build\outputs\apk\debug\app-debug.apk

echo.
echo === 3/3 Screenshot ===
adb shell screencap -p /sdcard/bp_shot.png
adb pull /sdcard/bp_shot.png "%~dp0screenshot.png"

echo.
echo ALL DONE. If gradle printed BUILD FAILED above, send me the log.
pause