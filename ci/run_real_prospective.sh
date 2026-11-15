#!/usr/bin/env bash
set -euo pipefail

# Same APK, JraSource, canonical/clock/capture and six SQLite stores as the Android app.
PKG=jp.personal.hanshinedge.validation
mkdir -p real-prospective-evidence
export_evidence() {
  adb exec-out run-as "$PKG" tar -C files/validation/real-prospective -cf - . \
    > real-prospective-evidence.tar 2>real-prospective-export-error.txt || return
  tar -xf real-prospective-evidence.tar -C real-prospective-evidence
}
trap export_evidence EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop "$PKG"
adb shell am instrument -w \
  -e class jp.personal.hanshinedge.validation.RealProspectiveObservationTest \
  -e observation_mode observe -e timeout_msec 9600000 \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee real-prospective-test.txt
export_evidence
grep -q 'OK (1 test)' real-prospective-test.txt
! grep -q 'FAILURES' real-prospective-test.txt
python3 ci/check_real_prospective.py real-prospective-evidence/real-prospective-summary.json
