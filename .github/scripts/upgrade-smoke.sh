#!/usr/bin/env bash
# Run on an emulator before a release is published.
#
# Installs the last published release and starts it, updates it to the build just made and
# starts that (twice), then installs the new build on its own. If the app crashes or isn't
# running after any start, the release is not published. This is the path that broke in 13.6:
# it started fine on a fresh install and crashed on the first start after an update.
#
# Usage: upgrade-smoke.sh <new.apk>
set -euo pipefail
NEW="$1"
PKG=app.redgrab

# Permissions are granted at install (-g). Otherwise the first start leaves Android's "allow notifications?"
# dialog on top, and a later start is handed to that leftover dialog instead of bringing the app up.
start() { # start <seconds to wait> <what this is>
  adb logcat -c
  # -S: from a stopped app, as after a real update.
  adb shell am start -W -S -n "$PKG/.MainActivity" > /dev/null
  sleep "$1"
  if adb logcat -d | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG"; then
    adb logcat -d | grep -A40 "FATAL EXCEPTION" | head -80
    echo "::error::$2 crashed on start"
    exit 1
  fi
  if ! adb shell pidof "$PKG" > /dev/null; then
    # Why it went: the app's own log lines, and what the system says about the process.
    adb logcat -d | grep -E "RedGrab|AndroidRuntime|$PKG|ActivityManager.*(died|Killing|ANR|Force stopping)|lowmemorykiller|libc.*Fatal" | tail -120
    echo "::error::$2 is not running after it was started"
    exit 1
  fi
  echo "ok: $2 started"
}

adb uninstall "$PKG" > /dev/null 2>&1 || true
mkdir -p prev
if gh release download --pattern '*.apk' --dir prev 2> /dev/null && ls prev/*.apk > /dev/null 2>&1; then
  old=$(ls prev/*.apk | head -1)
  echo "Updating from $(basename "$old")"
  adb install -g "$old" > /dev/null
  start 12 "the last release"
  adb shell am force-stop "$PKG"
  adb install -r -g "$NEW"
  adb shell dumpsys package "$PKG" | grep -m2 -E "versionName|versionCode" || true
  start 15 "this build, updated from the last release"
  adb shell am force-stop "$PKG"
  start 8 "this build, started a second time"
  adb uninstall "$PKG" > /dev/null
else
  echo "No earlier release to update from; only the fresh install is checked."
fi
adb install -g "$NEW" > /dev/null
start 15 "this build, installed fresh"
adb shell am force-stop "$PKG"
start 8 "this build, installed fresh, started a second time"
