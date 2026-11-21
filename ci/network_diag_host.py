"""Read-only host/emulator diagnostics. Never supplies race or odds data."""
import datetime
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request
import urllib.error

out = Path("real-prospective-evidence")
out.mkdir(exist_ok=True)
report = {"scope": "RUNNER_HOST_NETWORK_DIAGNOSTIC", "formal_observation_credit": 0,
          "observed_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "target": "https://www.jra.go.jp/", "DNS_RESOLVE": "NOT_VERIFIED",
          "HTTPS_CONNECT": "NOT_VERIFIED", "HTTP_RESPONSE": "NOT_VERIFIED",
          "HTTP_status": None,
          "proxy_environment_present": bool(os.getenv("HTTPS_PROXY") or os.getenv("https_proxy"))}

def alarm_handler(_signum, _frame):
    raise TimeoutError("HOST_DNS_TIMEOUT")

try:
    signal.signal(signal.SIGALRM, alarm_handler)
    signal.alarm(10)
    try:
        report["resolved_addresses"] = sorted({r[4][0] for r in socket.getaddrinfo("www.jra.go.jp", 443)})
        report["DNS_RESOLVE"] = "PASS"
    finally:
        signal.alarm(0)
    request = urllib.request.Request("https://www.jra.go.jp/", method="HEAD",
                                     headers={"User-Agent": "Mozilla/5.0 JRA-EDGE-NetworkPreflight/1.0"})
    with urllib.request.urlopen(request, timeout=10) as response:
        report.update(HTTPS_CONNECT="PASS", HTTP_RESPONSE="PASS", HTTP_status=response.status)
except urllib.error.HTTPError as error:
    report.update(HTTPS_CONNECT="PASS", HTTP_RESPONSE="PASS", HTTP_status=error.code,
                  error="HTTPError: " + str(error.code))
except Exception as error:
    report["error"] = type(error).__name__ + ": " + str(error)
    report["DNS_RESOLVE" if report["DNS_RESOLVE"] != "PASS" else "HTTPS_CONNECT"] = "FAIL"
(out / "host-network.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

def adb(*args):
    try:
        p = subprocess.run(["adb", *args], text=True, capture_output=True, timeout=10, check=False)
        return {"command": ["adb", *args], "returncode": p.returncode, "stdout": p.stdout, "stderr": p.stderr}
    except subprocess.TimeoutExpired:
        return {"command": ["adb", *args], "error": "ADB_DIAGNOSTIC_TIMEOUT"}

readiness = {"scope": "ANDROID_EMULATOR_READINESS", "adb_get_state": adb("get-state"),
             "net_dns1": adb("shell", "getprop", "net.dns1"),
             "net_dns2": adb("shell", "getprop", "net.dns2"), "connectivity_samples": []}
deadline = time.monotonic() + 30
while True:
    sample = adb("shell", "dumpsys", "connectivity")
    sample["observed_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    readiness["connectivity_samples"].append(sample)
    if "VALIDATED" in sample.get("stdout", ""):
        readiness["validated_text_observed"] = True
        break
    if time.monotonic() >= deadline:
        readiness["validated_text_observed"] = False
        break
    time.sleep(min(2, max(0, deadline - time.monotonic())))
# This text hint is not a DNS/TLS PASS. Native preflight proves the actual path.
(out / "emulator-network-readiness.json").write_text(json.dumps(readiness, indent=2), encoding="utf-8")
print("HOST_DNS=" + report["DNS_RESOLVE"] + " HOST_HTTPS=" + report["HTTPS_CONNECT"])
