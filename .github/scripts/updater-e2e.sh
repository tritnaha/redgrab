#!/usr/bin/env bash
# Run on an emulator after a release is published: does the app update itself?
#
# Installs the release before the latest one, opens it, sends it to the background and lets its
# periodic update check run. The app has to find the latest release on GitHub (without a token),
# download it and install it by itself. Passes when the installed build number becomes the
# latest release's.
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
jobs_of() { adb shell dumpsys jobscheduler | tr -d '\r' | sed -n "s/^ *JOB \([^#]*\)#u[0-9a-z]*\/\([0-9]*\): .*$PKG\/.*/\1 \2/p" | sort -u; }
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

adb uninstall "$PKG" > /dev/null 2>&1 || true
adb install -g "$(ls e2e/old/*.apk | head -1)" > /dev/null
echo "Installed build $(build); installer of record: $(adb shell pm list packages -i "$PKG" | tr -d '\r')"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
# On opening, the app looks for a release and downloads it; it installs only when it isn't in use.
sleep 45
adb shell input keyevent KEYCODE_HOME
sleep 3

for round in 1 2 3 4 5 6; do
  echo "Round $round: running the app's background jobs ($(jobs_of | tr '\n' ',' ))"
  run_jobs
  for i in $(seq 1 12); do
    sleep 5
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
  done
  # Android asks for a confirmation instead of installing silently: that is the app working, but not unattended.
  if adb logcat -d -s RedGrab:V | grep -q "is ready"; then echo "The app has the update and waits for a tap (Android wants a confirmation)."; fi
done
report
echo "::error::The app (build $(build)) did not update itself to build $want"
exit 1
