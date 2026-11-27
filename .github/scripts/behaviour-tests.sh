#!/usr/bin/env bash
# Runs the on-device tests (app/src/androidTest) on the emulator from the APKs the build step made.
#
# Installed with -g so every permission is granted: without it the first start leaves Android's
# "allow notifications?" dialog on top of the app, and each test waits minutes for a screen that
# never shows (that is what made this step run into its time limit before).
set -uo pipefail
PKG=app.redgrab
APP=app/build/outputs/apk/debug/app-debug.apk
TESTS=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb uninstall "$PKG" > /dev/null 2>&1 || true
adb uninstall "$PKG.test" > /dev/null 2>&1 || true
adb install -g "$APP" > /dev/null
adb install -g "$TESTS" > /dev/null
adb logcat -c
adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.log
if grep -q "^OK (" instrument.log; then exit 0; fi
echo "--- the app's log"
adb logcat -d -s RedGrab:V AndroidRuntime:E TestRunner:V | tail -150
echo "::error::On-device tests failed"
exit 1
