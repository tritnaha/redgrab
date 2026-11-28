#!/usr/bin/env bash
# Run on an emulator after a release is published: does the app update itself?
#
# Installs the release before the latest one and opens it. The app has to find the latest release
# on GitHub (without a token), download it and offer it; "Install" is tapped as you would. Passes
# when the installed build number becomes the latest release's and the app starts again.
#
# This is the app's own updater end to end, as an installed older version runs it. It needs two
# published releases and a network path from the emulator to GitHub.
set -uo pipefail
PKG=app.redgrab

# The app asks GitHub without a token. A private repository answers "not found" to that, and no
# installed app can find an update: say so at once instead of waiting for the emulator to show it.
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/$GITHUB_REPOSITORY/releases/latest")
if [ "$code" = 404 ]; then
  echo "::error::GitHub answers 404 for this repository's releases without a token: the repository is private. The app cannot update itself until the repository is public or a token is entered in the app."
  exit 1
fi
echo "Latest release without a token: HTTP $code"

tags=$(gh release list --limit 2 --json tagName --jq '.[].tagName')
new=$(echo "$tags" | sed -n 1p)
old=$(echo "$tags" | sed -n 2p)
if [ -z "$old" ]; then echo "Fewer than two releases; nothing to update from."; exit 0; fi
rm -rf e2e && mkdir -p e2e/old e2e/new
gh release download "$old" --pattern '*.apk' --dir e2e/old
# The build number is in the file name: RedGrab-<version>-<build>.apk
want=$(gh release view "$new" --json assets --jq '.assets[].name' | sed -n 's/.*-\([0-9]*\)\.apk$/\1/p' | head -1)
echo "Installing $old; it should update itself to $new (build $want)"

build() { adb shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
# The app's scheduled background work, as "<namespace:> <job id>" (the namespace is empty before Android 14).
# Android 16 prints "JOB androidx.work.systemjobscheduler:u0a216/1: ..."; older versions "JOB #u0a216/1: ...".
jobs_of() { adb shell dumpsys jobscheduler | tr -d '\r' | sed -n "s/^ *JOB \([^ #]*:\)\{0,1\}#\{0,1\}u[0-9a-z]*\/\([0-9]*\): .*$PKG\/.*/\1 \2/p" | sort -u; }
run_jobs() {
  jobs_of | while read -r a b; do
    if [ -z "${b:-}" ]; then adb shell cmd jobscheduler run -f "$PKG" "$a" > /dev/null 2>&1 || true
    else adb shell cmd jobscheduler run -f -n "${a%:}" "$PKG" "$b" > /dev/null 2>&1 || true; fi
  done
}
report() {
  echo "--- the app's log (builds from 13.12 on keep it out of the system log)"
  adb logcat -d -s RedGrab:V | tail -60
  echo "--- installer"
  adb logcat -d | grep -iE "PackageInstaller|InstallPackageHelper|INSTALL_FAILED|PackageManager.*$PKG" | tail -30
}

# Taps the middle of the first thing on screen whose text is exactly $1. Fails when nothing has that text.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || return 1
  local xy
  xy=$(adb shell cat /sdcard/ui.xml | python3 -c '
import re, sys
m = re.search(r"text=\"%s\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"" % re.escape(sys.argv[1]), sys.stdin.read())
if not m: sys.exit(1)
a = [int(v) for v in m.groups()]
print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)' "$1") || return 1
  adb shell input tap $xy
}
on_screen() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\{2,60\}"' | head -25 | tr '\n' ' '; }

adb uninstall "$PKG" > /dev/null 2>&1 || true
adb install -g "$(ls e2e/old/*.apk | head -1)" > /dev/null
# Android asks once whether this app may install apps ("Install unknown apps"); on a phone you
# answer that the first time. Here it is answered beforehand.
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
echo "Installed build $(build); installer of record: $(adb shell pm list packages -i "$PKG" | tr -d '\r')"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
# On opening, the app looks for a release, downloads it and offers it in a dialog: "Install".
# Android then installs it (an app that updates itself needs no further confirmation; if a
# system dialog does come up, its button is tapped too).
done_check() {
  if [ "$(build)" = "$want" ]; then
    echo "ok: the app updated itself to build $want"
    adb logcat -c
    adb shell am start -W -S -n "$PKG/.MainActivity" > /dev/null
    sleep 15
    if adb logcat -d | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG" || ! adb shell pidof "$PKG" > /dev/null; then
      report; echo "::error::The app updated itself but doesn't start afterwards"; exit 1
    fi
    echo "ok: it starts after updating itself"
    exit 0
  fi
}
tapped=no
for i in $(seq 1 40); do
  sleep 5
  done_check
  if [ "$tapped" = no ] && tap_text "Install"; then tapped=yes; echo "The app offered the update; tapped Install (after about $((i * 5)) s)"; continue; fi
  # Android's own confirmation, should it ask.
  if [ "$tapped" = yes ]; then tap_text "Update" || tap_text "Install" || tap_text "OK" || true; fi
done
echo "On screen: $(on_screen)"

# The other way in: with the app in the background, its periodic check installs by itself.
adb shell input keyevent KEYCODE_HOME
sleep 3
echo "Scheduled work as the system lists it: $(adb shell dumpsys jobscheduler | tr -d '\r' | grep -m4 "$PKG/" | tr '\n' ' ')"
for round in 1 2 3; do
  echo "Round $round: running the app's background jobs ($(jobs_of | tr '\n' ','))"
  run_jobs
  for i in $(seq 1 12); do sleep 5; done_check; done
done
report
echo "::error::The app (build $(build)) did not update itself to build $want"
exit 1
