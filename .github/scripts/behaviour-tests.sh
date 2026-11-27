#!/usr/bin/env bash
# Runs the on-device tests (app/src/androidTest) on the emulator from the APKs the build job made.
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
# A freshly booted emulator is still busy for a while; a test process started into that can be killed.
adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
sleep 15

run() {
  adb logcat -c
  adb logcat -b crash -c > /dev/null 2>&1 || true
  adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.log
  grep -q "^OK (" instrument.log
}
why() {
  echo "--- crashes"
  adb logcat -d -b crash | tail -80
  echo "--- the system on this app"
  adb logcat -d | grep -E "AndroidRuntime|TestRunner|ActivityManager.*($PKG|Process .* has died|Killing)|lowmemorykiller|am_crash|$PKG.*(died|crash)" | tail -60
}

if run; then exit 0; fi
why
# The process going away before a single test ran is the emulator, not the tests: once more.
if grep -q "Process crashed" instrument.log && ! grep -q "^Tests run\|^Time:" instrument.log; then
  echo "The test process ended before any test ran; trying once more."
  sleep 20
  if run; then exit 0; fi
  why
fi
echo "::error::On-device tests failed"
exit 1
