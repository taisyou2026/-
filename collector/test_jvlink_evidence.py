from __future__ import annotations

import ast
import copy
import json
import pathlib
import unittest

from collector.jvlink_evidence import (
    CONTRACT_SCHEMA,
    EVIDENCE_SCHEMA,
    LEGACY_SCHEMA,
    PROGID,
    compute_evidence_sha256,
    evaluate,
    legacy_v1_to_v2,
    seal_evidence,
    seal_readiness,
)


ROOT = pathlib.Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "collector" / "fixtures" / "jvlink-preflight-evidence-synthetic.json"


def valid_evidence():
    return seal_evidence({
        "schema": EVIDENCE_SCHEMA,
        "checked_at": "2026-11-23T00:00:00+00:00",
        "platform": {
            "os_name": "Windows",
            "os_release": "11",
            "python_version": "3.14.0",
            "python_bitness": 64,
        },
        "runtime": {
            "progid": PROGID,
            "pywin32": True,
            "com_dispatch": True,
            "jvlink_version": "5.0.0",
            "jvinit_called": True,
            "jvinit_code": 0,
            "producer_status": "PASS",
        },
        "safety": {
            "network_data_download": "NONE",
            "jvopen_called": False,
            "jvrtopen_called": False,
            "secret_redaction_status": "PASS",
        },
        "tool": {
            "producer": "collector/jvlink_no_download_preflight.py",
            "script_version": "1.0.0",
            "source_schema": None,
            "source_evidence_sha256": None,
        },
        "synthetic_test_only": False,
    })


def valid_contract():
    return {
        "schema": CONTRACT_SCHEMA,
        "checked_at": "2026-11-23T00:01:00+00:00",
        "active_user_contract": True,
        "valid_user_key": True,
        "private_use_only": True,
        "credential_material_recorded": False,
        "notes": "Boolean attestation only; no credential material.",
    }


def reseal(e):
    return seal_evidence({k: v for k, v in e.items() if k != "evidence_sha256"})


class JvLinkEvidenceTest(unittest.TestCase):
    def test_valid_technical_preflight_does_not_overclaim_contract_or_smoke_readiness(self):
        r = seal_readiness(evaluate(valid_evidence(), valid_contract()))
        self.assertEqual("VERIFIED", r["JRA_VAN_PREFLIGHT_EVIDENCE"])
        self.assertEqual("VERIFIED", r["WINDOWS_JVLINK_RUNTIME"])
        self.assertEqual("VERIFIED", r["PYWIN32"])
        self.assertEqual("VERIFIED", r["COM_DISPATCH"])
        self.assertEqual("VERIFIED", r["JVINIT"])
        self.assertEqual("NONE", r["NETWORK_DATA_DOWNLOAD"])
        self.assertEqual("VERIFIED", r["CONTRACT_ATTESTATION"])
        self.assertEqual("NOT_VERIFIED", r["ACTIVE_USER_CONTRACT"])
        self.assertEqual("NOT_VERIFIED", r["ACTIVE_USER_KEY"])
        self.assertEqual("NO", r["SMOKE_TEST_READY"])
        self.assertEqual("NO", r["BULK_BACKFILL_AUTHORIZED"])
        self.assertEqual("NOT_AVAILABLE", r["F0_STATUS"])
        self.assertEqual("BLOCKED", r["FORMAL_PAPER_BUY"])
        self.assertEqual("BLOCKED", r["REAL_MONEY"])
        self.assertEqual("DISABLED", r["AUTO_REAL_BET"])

    def test_non_windows_is_rejected(self):
        e = valid_evidence()
        e["platform"]["os_name"] = "Linux"
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("WINDOWS_REQUIRED", r["technical_reasons"])
        self.assertEqual("NOT_VERIFIED", r["WINDOWS_JVLINK_RUNTIME"])

    def test_missing_pywin32_is_rejected(self):
        e = valid_evidence()
        e["runtime"]["pywin32"] = False
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("PYWIN32_NOT_READY", r["technical_reasons"])

    def test_com_dispatch_failure_is_rejected(self):
        e = valid_evidence()
        e["runtime"]["com_dispatch"] = False
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("JVLINK_COM_NOT_READY", r["technical_reasons"])

    def test_invalid_jvinit_result_is_rejected(self):
        e = valid_evidence()
        e["runtime"]["jvinit_code"] = -1
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("JVINIT_NOT_READY", r["technical_reasons"])

    def test_network_download_violation_is_rejected(self):
        e = valid_evidence()
        e["safety"]["network_data_download"] = "DETECTED"
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("NETWORK_DATA_DOWNLOAD_VIOLATION", r["technical_reasons"])
        self.assertEqual("NO", r["SMOKE_TEST_READY"])

    def test_malformed_evidence_hash_is_rejected(self):
        e = valid_evidence()
        e["evidence_sha256"] = "bad"
        r = evaluate(e, valid_contract())
        self.assertIn("EVIDENCE_SHA256_INVALID", r["technical_reasons"])

    def test_missing_required_field_is_rejected(self):
        e = valid_evidence()
        del e["platform"]
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("PLATFORM_REQUIRED", r["technical_reasons"])

    def test_schema_mismatch_is_rejected(self):
        e = valid_evidence()
        e["schema"] = "OTHER"
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("EVIDENCE_SCHEMA_MISMATCH", r["technical_reasons"])


    def test_stale_schema_epoch_evidence_is_rejected(self):
        e = valid_evidence()
        e["checked_at"] = "2026-11-22T23:59:59+00:00"
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("EVIDENCE_STALE_SCHEMA_EPOCH", r["technical_reasons"])

    def test_non_pass_producer_status_is_rejected(self):
        e = valid_evidence()
        e["runtime"]["producer_status"] = "JVINIT_NOT_READY"
        r = evaluate(reseal(e), valid_contract())
        self.assertIn("PRODUCER_STATUS_NOT_PASS", r["technical_reasons"])

    def test_hash_and_readiness_are_deterministic(self):
        a = valid_evidence()
        b = valid_evidence()
        self.assertEqual(a["evidence_sha256"], b["evidence_sha256"])
        self.assertEqual(compute_evidence_sha256(a), compute_evidence_sha256(b))
        ra = seal_readiness(evaluate(a, valid_contract()))
        rb = seal_readiness(evaluate(b, valid_contract()))
        self.assertEqual(ra["readiness_sha256"], rb["readiness_sha256"])
        self.assertEqual(ra, rb)

    def test_secret_bearing_evidence_is_rejected(self):
        e = valid_evidence()
        e["runtime"]["user_key"] = "SHOULD-NOT-APPEAR"
        r = evaluate(reseal(e), valid_contract())
        self.assertTrue(any(x.startswith("SECRET_MATERIAL_DETECTED:") for x in r["technical_reasons"]))

    def test_technical_success_without_contract_never_enables_smoke_or_bulk(self):
        r = evaluate(valid_evidence(), None)
        self.assertEqual("VERIFIED", r["WINDOWS_JVLINK_RUNTIME"])
        self.assertEqual("NO", r["SMOKE_TEST_READY"])
        self.assertEqual("NO", r["BULK_BACKFILL_AUTHORIZED"])
        self.assertEqual("NOT_VERIFIED", r["ACTIVE_USER_CONTRACT"])
        self.assertEqual("NOT_VERIFIED", r["ACTIVE_USER_KEY"])

    def test_legacy_historical_v1_is_wrapped_without_claiming_bulk_authorization(self):
        legacy = {
            "schema": LEGACY_SCHEMA,
            "checked_at": "2026-11-23T00:00:00+00:00",
            "network_data_download": "NONE",
            "jvopen_called": False,
            "jvrtopen_called": False,
            "os_name": "Windows",
            "os_release": "11",
            "python_version": "3.14.0",
            "python_bitness": 64,
            "progid": PROGID,
            "pywin32": True,
            "com_dispatch": True,
            "jvinit_called": True,
            "jvinit_code": 0,
            "status": "PASS",
        }
        e = legacy_v1_to_v2(legacy)
        self.assertEqual(EVIDENCE_SCHEMA, e["schema"])
        self.assertEqual(LEGACY_SCHEMA, e["tool"]["source_schema"])
        r = evaluate(e, valid_contract())
        self.assertEqual("VERIFIED", r["WINDOWS_JVLINK_RUNTIME"])
        self.assertEqual("NO", r["SMOKE_TEST_READY"])
        self.assertEqual("NO", r["BULK_BACKFILL_AUTHORIZED"])


    def test_windows_producer_contains_no_jvopen_or_jvrtopen_call(self):
        producer = (ROOT / "collector" / "jvlink_no_download_preflight.py").read_text(encoding="utf-8")
        tree = ast.parse(producer)
        forbidden = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                fn = node.func
                if isinstance(fn, ast.Attribute) and fn.attr in {"JVOpen", "JVRTOpen"}:
                    forbidden.append(fn.attr)
        self.assertEqual([], forbidden)

    def test_committed_sample_is_redacted_and_cannot_impersonate_real_evidence(self):
        e = json.loads(SAMPLE.read_text(encoding="utf-8"))
        self.assertEqual(compute_evidence_sha256(e), e["evidence_sha256"])
        self.assertTrue(e["synthetic_test_only"])
        r = evaluate(e, valid_contract())
        self.assertIn("SYNTHETIC_EVIDENCE_NOT_REAL", r["technical_reasons"])
        self.assertEqual("NOT_VERIFIED", r["WINDOWS_JVLINK_RUNTIME"])
        self.assertEqual("NOT_VERIFIED", r["PYWIN32"])
        self.assertEqual("NOT_VERIFIED", r["COM_DISPATCH"])
        self.assertEqual("NOT_VERIFIED", r["JVINIT"])
        self.assertEqual("UNKNOWN", r["NETWORK_DATA_DOWNLOAD"])


if __name__ == "__main__":
    unittest.main()
