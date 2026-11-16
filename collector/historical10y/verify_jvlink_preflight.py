#!/usr/bin/env python3
"""Fail-closed verifier for Windows JV-Link preflight evidence.

This verifier never talks to JV-Link or the network. It only reads evidence
JSON files produced elsewhere and determines whether the source gate may
advance from PARTIAL to PASS.
"""
from __future__ import annotations

import argparse
import json
import pathlib
from typing import Any


REQUIRED_PREFLIGHT = {
    "schema": "JRA_VAN_JVLINK_PREFLIGHT-v1",
    "os_name": "Windows",
    "pywin32": True,
    "com_dispatch": True,
    "jvinit_called": True,
    "jvinit_code": 0,
    "status": "PASS",
    "network_data_download": "NONE",
    "jvopen_called": False,
    "jvrtopen_called": False,
}


def evaluate(preflight: dict[str, Any], contract: dict[str, Any] | None) -> dict[str, Any]:
    reasons: list[str] = []

    for key, expected in REQUIRED_PREFLIGHT.items():
        actual = preflight.get(key)
        if actual != expected:
            reasons.append(f"PREFLIGHT_{key.upper()}_MISMATCH:{actual!r}!={expected!r}")

    if preflight.get("python_bitness") not in (32, 64):
        reasons.append("PYTHON_BITNESS_INVALID")

    contract = contract or {}
    if contract.get("active_user_contract") is not True:
        reasons.append("ACTIVE_USER_CONTRACT_NOT_VERIFIED")
    if contract.get("valid_user_key") is not True:
        reasons.append("ACTIVE_USER_KEY_NOT_VERIFIED")
    if contract.get("private_use_only") is not True:
        reasons.append("PRIVATE_USE_ATTESTATION_NOT_VERIFIED")

    passed = not reasons
    return {
        "schema": "JRA_VAN_JVLINK_GATE_RESULT-v1",
        "WINDOWS_JVLINK_PREFLIGHT": "PASS" if passed else "NOT_PASS",
        "JRA_VAN_BULK_SOURCE_GATE": "PASS" if passed else "PARTIAL",
        "SOURCE_MANIFEST_READINESS": "PASS" if passed else "PARTIAL",
        "smoke_test_ready": passed,
        "bulk_backfill_authorized": False,
        "historical_smoke_authorized": passed,
        "reasons": reasons,
        "evidence": {
            "preflight_status": preflight.get("status"),
            "os_name": preflight.get("os_name"),
            "python_version": preflight.get("python_version"),
            "python_bitness": preflight.get("python_bitness"),
            "pywin32": preflight.get("pywin32"),
            "com_dispatch": preflight.get("com_dispatch"),
            "jvinit_code": preflight.get("jvinit_code"),
            "network_data_download": preflight.get("network_data_download"),
            "jvopen_called": preflight.get("jvopen_called"),
            "jvrtopen_called": preflight.get("jvrtopen_called"),
            "active_user_contract": contract.get("active_user_contract"),
            "valid_user_key": contract.get("valid_user_key"),
            "private_use_only": contract.get("private_use_only"),
        },
        "guards": {
            "REAL_PROSPECTIVE": False,
            "data_status": "HISTORICAL_ONLY",
            "REAL_MONEY": "BLOCKED",
            "AUTO_REAL_BET": "DISABLED",
        },
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preflight", required=True)
    ap.add_argument("--contract")
    ap.add_argument("--output", default="jvlink-preflight-gate.json")
    args = ap.parse_args()

    preflight = json.loads(pathlib.Path(args.preflight).read_text(encoding="utf-8"))
    contract = None
    if args.contract:
        contract = json.loads(pathlib.Path(args.contract).read_text(encoding="utf-8"))

    result = evaluate(preflight, contract)
    pathlib.Path(args.output).write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["WINDOWS_JVLINK_PREFLIGHT"] == "PASS" else 5


if __name__ == "__main__":
    raise SystemExit(main())
