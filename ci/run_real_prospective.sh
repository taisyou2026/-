#!/usr/bin/env bash
set -euo pipefail

# Same APK, JraSource, canonical/clock/capture and six SQLite stores as the Android app.
PKG=jp.personal.hanshinedge.validation
mkdir -p real-prospective-evidence
python3 - <<'PY'
import json, os, subprocess
from pathlib import Path
Path('real-prospective-evidence/run-identity.json').write_text(json.dumps({
    'run_id': os.getenv('GITHUB_RUN_ID'), 'event': os.getenv('GITHUB_EVENT_NAME'),
    'scheduler_sha': os.getenv('GITHUB_SHA'),
    'collector_sha': subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
},indent=2))
PY
export_evidence() {
  adb exec-out run-as "$PKG" tar -C files/validation/real-prospective -cf - . \
    > real-prospective-evidence.tar 2>real-prospective-export-error.txt || return
  tar -xf real-prospective-evidence.tar -C real-prospective-evidence
}
trap export_evidence EXIT
python3 ci/network_diag_host.py
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop "$PKG"
adb shell am instrument -w \
  -e class jp.personal.hanshinedge.prospective.NetworkPreflightTest \
  -e network_diagnostics readonly -e timeout_msec 180000 \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee real-prospective-evidence/network-preflight-test.txt
adb shell am instrument -w \
  -e class jp.personal.hanshinedge.validation.RealProspectiveObservationTest \
  -e observation_mode observe -e timeout_msec 9600000 \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee real-prospective-test.txt
export_evidence
grep -q 'OK (1 test)' real-prospective-test.txt
! grep -q 'FAILURES' real-prospective-test.txt
grep -q 'OK (1 test)' real-prospective-evidence/network-preflight-test.txt
! grep -q 'FAILURES' real-prospective-evidence/network-preflight-test.txt
python3 ci/check_network_preflight.py
python3 ci/check_real_prospective.py real-prospective-evidence/real-prospective-summary.json
