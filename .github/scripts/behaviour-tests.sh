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
# A freshly booted emulator is still busy for a while: a test process started into that can be killed,
# and the system can put "... isn't responding" dialogs over the app. Wait for the home screen, keep
# such dialogs away, and start from a clear screen.
adb shell settings put global hide_error_dialogs 1 > /dev/null 2>&1 || true
adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
for i in $(seq 1 30); do
  adb shell dumpsys activity activities 2> /dev/null | grep -qE "(mResumedActivity|topResumedActivity|ResumedActivity).*(auncher|home)" && break
  sleep 3
done
adb shell input keyevent KEYCODE_HOME > /dev/null 2>&1 || true
sleep 10
echo "In front before the tests: $(adb shell dumpsys activity activities 2> /dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' | tr -d '\r')"

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

# The pictures the tests took (the README's screenshots), fetched whether the tests passed or not.
shots() {
  rm -rf shots && mkdir -p shots
  adb exec-out run-as "$PKG" sh -c "cd /sdcard/Android/data/$PKG/files 2>/dev/null && tar cf - shots" 2> /dev/null | tar xf - 2> /dev/null || true
  if [ -z "$(ls shots 2> /dev/null)" ]; then adb root > /dev/null 2>&1; sleep 3; adb pull "/sdcard/Android/data/$PKG/files/shots" . > /dev/null 2>&1 || true; fi
  echo "Screenshots fetched: $(ls shots 2> /dev/null | tr '\n' ' ')"
}

if run; then shots; exit 0; fi
why
# The process going away before a single test ran is the emulator, not the tests: once more.
if grep -q "Process crashed" instrument.log && ! grep -q "^Tests run\|^Time:" instrument.log; then
  echo "The test process ended before any test ran; trying once more."
  sleep 20
  if run; then shots; exit 0; fi
  why
fi
shots
echo "::error::On-device tests failed"
exit 1
