from __future__ import annotations

import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class SourceReadinessEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.controls = json.loads((ROOT / "expected_counts.json").read_text(encoding="utf-8"))
        self.matrix = json.loads((ROOT / "source_capability_matrix.json").read_text(encoding="utf-8"))

    def test_2017_control_totals(self):
        y = self.controls["year_2017"]
        self.assertEqual(288, y["days"])
        self.assertEqual(3455, y["races"])
        self.assertEqual(3455, sum(y["by_venue"].values()))
        self.assertEqual(3455, y["flat_races"] + y["obstacle_races"])

    def test_2017_2025_total(self):
        annual = self.controls["annual_races"]
        total = sum(int(annual[str(y)]) for y in range(2017, 2026))
        self.assertEqual(31094, total)
        self.assertEqual(31094, self.controls["races_2017_2025"])

    def test_gate_is_fail_closed_on_terms(self):
        verdict = self.controls["verdict"]
        self.assertEqual("PASS", verdict["manifest_discovery"])
        self.assertEqual("PASS", verdict["expected_count_method"])
        self.assertEqual("PARTIAL", verdict["source_manifest_readiness"])
        self.assertFalse(verdict["smoke_test_ready"])
        self.assertFalse(verdict["bulk_backfill_authorized"])

    def test_no_nonofficial_source_is_canonical(self):
        for source in self.matrix["sources"]:
            if source["canonical_use"].startswith("SUPPORTED") or source["canonical_use"].startswith("PREFERRED"):
                self.assertTrue(source["official"])


if __name__ == "__main__":
    unittest.main()
