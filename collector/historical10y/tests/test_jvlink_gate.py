from __future__ import annotations
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from jvlink_preflight import classify


class JvLinkGateTest(unittest.TestCase):
    def setUp(self):
        self.gate = json.loads((ROOT / "jvlink_source_gate.json").read_text(encoding="utf-8"))
        self.mapping = json.loads((ROOT / "jvlink_record_mapping.json").read_text(encoding="utf-8"))

    def test_source_capability_is_confirmed_but_runtime_is_fail_closed(self):
        self.assertEqual("PARTIAL", self.gate["verdict"]["JRA_VAN_BULK_SOURCE_GATE"])
        self.assertFalse(self.gate["verdict"]["smoke_test_ready"])
        self.assertFalse(self.gate["verdict"]["bulk_backfill_authorized"])
        self.assertEqual("NOT_TESTED", self.gate["runtime"]["local_runtime_execution"])

    def test_target_2017_is_covered(self):
        self.assertTrue(self.gate["coverage"]["target_2017_full_coverage"])
        self.assertEqual("1986+", self.gate["coverage"]["RA"])
        self.assertEqual("1986+", self.gate["coverage"]["SE"])
        self.assertEqual("1986+", self.gate["coverage"]["HR"])

    def test_required_canonical_records_present(self):
        required = {x["record_type"] for x in self.mapping["records"] if x["required_for_canonical"]}
        self.assertTrue({"RA", "SE", "HR", "UM", "KS", "CH"}.issubset(required))

    def test_canonical_key_mapping_is_unique(self):
        self.assertEqual("PASS", self.mapping["official_key_mapping"]["unique_mapping"])
        self.assertEqual(
            "YYYY + venue_code(2) + meeting(2) + day(2) + race_no(2)",
            self.mapping["canonical_race_id"]
        )

    def test_preflight_classifier(self):
        self.assertEqual("WINDOWS_REQUIRED", classify("Linux", False, False, None))
        self.assertEqual("PYWIN32_NOT_READY", classify("Windows", False, False, None))
        self.assertEqual("JVLINK_COM_NOT_READY", classify("Windows", True, False, None))
        self.assertEqual("JVINIT_NOT_READY", classify("Windows", True, True, -303))
        self.assertEqual("PASS", classify("Windows", True, True, 0))

    def test_historical_does_not_authorize_prospective_timechain(self):
        self.assertFalse(self.mapping["historical_leakage_rule"]["REAL_PROSPECTIVE"])
        self.assertIn("MUST_NOT", self.mapping["historical_leakage_rule"]["prospective_t20_t10_t5_t2"])


if __name__ == "__main__":
    unittest.main()
