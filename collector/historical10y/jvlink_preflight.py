#!/usr/bin/env python3
"""Read-only Windows/JV-Link preflight.

No JVOpen/JVRTOpen call is made. This script only proves the local Windows
runtime path required before the bounded 2017 smoke test.
"""
from __future__ import annotations

import argparse
import json
import platform
import struct
import sys
from datetime import datetime, timezone


def classify(os_name: str, pywin32_ok: bool, com_ok: bool, jvinit_code):
    if os_name != "Windows":
        return "WINDOWS_REQUIRED"
    if not pywin32_ok:
        return "PYWIN32_NOT_READY"
    if not com_ok:
        return "JVLINK_COM_NOT_READY"
    if jvinit_code != 0:
        return "JVINIT_NOT_READY"
    return "PASS"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", default="jvlink-preflight.json")
    ap.add_argument("--skip-jvinit", action="store_true")
    args = ap.parse_args()

    report = {
        "schema": "JRA_VAN_JVLINK_PREFLIGHT-v1",
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "network_data_download": "NONE",
        "jvopen_called": False,
        "jvrtopen_called": False,
        "os_name": platform.system(),
        "os_release": platform.release(),
        "python_version": platform.python_version(),
        "python_bitness": struct.calcsize("P") * 8,
        "progid": "JVDTLab.JVLink",
        "pywin32": False,
        "com_dispatch": False,
        "jvinit_called": False,
        "jvinit_code": None,
        "status": "NOT_RUN"
    }

    if platform.system() != "Windows":
        report["status"] = classify(platform.system(), False, False, None)
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 2

    try:
        import win32com.client
        report["pywin32"] = True
    except Exception as exc:
        report["error"] = f"pywin32:{type(exc).__name__}:{exc}"
        report["status"] = classify("Windows", False, False, None)
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 3

    try:
        jv = win32com.client.Dispatch("JVDTLab.JVLink")
        report["com_dispatch"] = True
        if args.skip_jvinit:
            report["status"] = "COM_PASS_JVINIT_SKIPPED"
        else:
            report["jvinit_called"] = True
            code = int(jv.JVInit("UNKNOWN"))
            report["jvinit_code"] = code
            report["status"] = classify("Windows", True, True, code)
    except Exception as exc:
        report["error"] = f"JVLink:{type(exc).__name__}:{exc}"
        report["status"] = classify(
            "Windows", report["pywin32"], report["com_dispatch"], report["jvinit_code"]
        )

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASS" else 4


if __name__ == "__main__":
    raise SystemExit(main())
