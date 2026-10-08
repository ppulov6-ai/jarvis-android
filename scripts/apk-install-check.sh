#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p evidence
trap 'adb logcat -d -v threadtime > evidence/logcat.txt || true; adb shell dumpsys package ru.pulat.jarvis > evidence/package.txt || true' EXIT
adb shell getprop ro.build.version.sdk | tee evidence/sdk.txt
adb shell getprop ro.product.cpu.abilist | tee evidence/abis.txt
adb shell getconf PAGE_SIZE | tee evidence/page-size.txt
for pass in 1 2; do
  for version in old new; do
    adb uninstall ru.pulat.jarvis || true
    adb shell pm list packages ru.pulat.jarvis > evidence/absent-$version-$pass.txt
    if grep -q '^package:ru.pulat.jarvis$' evidence/absent-$version-$pass.txt; then exit 1; fi
    adb logcat -c
    adb install --no-streaming "$version.apk" 2>&1 | tee "evidence/$version-clean-$pass.txt"
    grep -q '^Success$' "evidence/$version-clean-$pass.txt"
    adb shell dumpsys package ru.pulat.jarvis > "evidence/$version-package-$pass.txt"
    adb uninstall ru.pulat.jarvis
  done
done
adb install --no-streaming old.apk
set +e
adb install --no-streaming -r new.apk > evidence/update.txt 2>&1
update_rc=$?
set -e
cat evidence/update.txt
test "$update_rc" -ne 0
grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE evidence/update.txt
