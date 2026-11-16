from __future__ import annotations

import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from verify_jvlink_preflight import evaluate


def good_preflight():
    return {
        "schema": "JRA_VAN_JVLINK_PREFLIGHT-v1",
        "os_name": "Windows",
        "python_version": "3.14.0",
        "python_bitness": 64,
        "pywin32": True,
        "com_dispatch": True,
        "jvinit_called": True,
        "jvinit_code": 0,
        "status": "PASS",
        "network_data_download": "NONE",
        "jvopen_called": False,
        "jvrtopen_called": False,
    }


def good_contract():
    return {
        "active_user_contract": True,
        "valid_user_key": True,
        "private_use_only": True,
    }


class JvLinkEvidenceVerifierTest(unittest.TestCase):
    def test_all_evidence_promotes_gate_but_not_bulk_full_run(self):
        out = evaluate(good_preflight(), good_contract())
        self.assertEqual("PASS", out["WINDOWS_JVLINK_PREFLIGHT"])
        self.assertEqual("PASS", out["JRA_VAN_BULK_SOURCE_GATE"])
        self.assertEqual("PASS", out["SOURCE_MANIFEST_READINESS"])
        self.assertTrue(out["smoke_test_ready"])
        self.assertTrue(out["historical_smoke_authorized"])
        self.assertFalse(out["bulk_backfill_authorized"])
        self.assertEqual("BLOCKED", out["guards"]["REAL_MONEY"])

    def test_missing_contract_fails_closed(self):
        out = evaluate(good_preflight(), None)
        self.assertEqual("NOT_PASS", out["WINDOWS_JVLINK_PREFLIGHT"])
        self.assertFalse(out["smoke_test_ready"])
        self.assertIn("ACTIVE_USER_CONTRACT_NOT_VERIFIED", out["reasons"])
        self.assertIn("ACTIVE_USER_KEY_NOT_VERIFIED", out["reasons"])

    def test_jvinit_failure_fails_closed(self):
        pre = good_preflight()
        pre["jvinit_code"] = -303
        pre["status"] = "JVINIT_NOT_READY"
        out = evaluate(pre, good_contract())
        self.assertEqual("PARTIAL", out["JRA_VAN_BULK_SOURCE_GATE"])
        self.assertFalse(out["historical_smoke_authorized"])

    def test_any_data_open_signal_fails_closed(self):
        pre = good_preflight()
        pre["jvopen_called"] = True
        out = evaluate(pre, good_contract())
        self.assertEqual("NOT_PASS", out["WINDOWS_JVLINK_PREFLIGHT"])
        self.assertTrue(any("JVOPEN_CALLED" in reason for reason in out["reasons"]))

    def test_linux_evidence_cannot_pass(self):
        pre = good_preflight()
        pre["os_name"] = "Linux"
        pre["status"] = "WINDOWS_REQUIRED"
        out = evaluate(pre, good_contract())
        self.assertEqual("PARTIAL", out["SOURCE_MANIFEST_READINESS"])
        self.assertFalse(out["smoke_test_ready"])


if __name__ == "__main__":
    unittest.main()
