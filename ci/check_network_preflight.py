"""Classify actual host/native diagnostics; never imports data into Runtime."""
import json
from pathlib import Path

p = Path("real-prospective-evidence")
host = json.loads((p / "host-network.json").read_text())
emulator = json.loads((p / "network-preflight.json").read_text())
host_ok = (host["DNS_RESOLVE"] == "PASS" and host["HTTPS_CONNECT"] == "PASS"
           and isinstance(host["HTTP_status"], int) and 200 <= host["HTTP_status"] < 400)
emulator_ok = emulator["NetworkPreflight"] == "PASS"
comparison = {"classification": ("HOST_OK" if host_ok else "HOST_FAIL") + "/" +
              ("EMULATOR_OK" if emulator_ok else "EMULATOR_FAIL"),
              "HOST_DNS": host["DNS_RESOLVE"], "EMULATOR_DNS": emulator["DNS_RESOLVE"],
              "HTTPS": emulator["HTTPS_CONNECT"], "HTTP": emulator["HTTP_status"],
              "NetworkPreflight": emulator["NetworkPreflight"], "formal_observation_credit": 0}
(p / "network-comparison.json").write_text(json.dumps(comparison, indent=2))
print(json.dumps(comparison))
assert emulator_ok, "NETWORK_PREFLIGHT_FAIL: inspect saved host/native diagnostics"
