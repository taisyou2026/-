#!/usr/bin/env python3
"""Windows-only no-download JV-Link evidence producer.

No JVOpen/JVRTOpen call exists in this file. It only checks pywin32, COM
Dispatch and JVInit("UNKNOWN"), then emits sealed evidence for offline
verification.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import platform
import struct
from datetime import datetime, timezone

if __package__ in (None, ""):
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from collector.jvlink_evidence import EVIDENCE_SCHEMA, PROGID, evaluate, seal_evidence


SCRIPT_VERSION = "1.0.0"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", default="jvlink-preflight-evidence.json")
    args = ap.parse_args()

    runtime = {
        "progid": PROGID,
        "pywin32": False,
        "com_dispatch": False,
        "jvlink_version": None,
        "jvinit_called": False,
        "jvinit_code": None,
        "producer_status": "NOT_RUN",
    }
    evidence = {
        "schema": EVIDENCE_SCHEMA,
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "platform": {
            "os_name": platform.system(),
            "os_release": platform.release(),
            "python_version": platform.python_version(),
            "python_bitness": struct.calcsize("P") * 8,
        },
        "runtime": runtime,
        "safety": {
            "network_data_download": "NONE",
            "jvopen_called": False,
            "jvrtopen_called": False,
            "secret_redaction_status": "PASS",
        },
        "tool": {
            "producer": "collector/jvlink_no_download_preflight.py",
            "script_version": SCRIPT_VERSION,
            "source_schema": None,
            "source_evidence_sha256": None,
        },
        "synthetic_test_only": False,
    }

    if platform.system() != "Windows":
        runtime["producer_status"] = "WINDOWS_REQUIRED"
    else:
        try:
            import win32com.client
            runtime["pywin32"] = True
            try:
                jv = win32com.client.Dispatch(PROGID)
                runtime["com_dispatch"] = True
                for attr in ("JVLinkVersion", "m_JVLinkVersion"):
                    try:
                        value = getattr(jv, attr)
                        if value:
                            runtime["jvlink_version"] = str(value)
                            break
                    except Exception:
                        pass
                runtime["jvinit_called"] = True
                runtime["jvinit_code"] = int(jv.JVInit("UNKNOWN"))
                runtime["producer_status"] = "PASS" if runtime["jvinit_code"] == 0 else "JVINIT_NOT_READY"
            except Exception as exc:
                runtime["producer_status"] = f"JVLINK_COM_ERROR:{type(exc).__name__}"
        except Exception as exc:
            runtime["producer_status"] = f"PYWIN32_ERROR:{type(exc).__name__}"

    evidence = seal_evidence(evidence)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(evidence, f, ensure_ascii=False, sort_keys=True, indent=2)
        f.write("\n")

    result = evaluate(evidence, None)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    # Exit 0 indicates local technical payload success, not independent provenance.
    return 0 if result["JRA_VAN_PREFLIGHT_EVIDENCE"] == "VERIFIED" else 4


if __name__ == "__main__":
    raise SystemExit(main())
