from __future__ import annotations

import copy
import json
import pathlib
import unittest

from collector.audit_fundamental_feature_readiness import (
    SCHEMA,
    audit_fixture,
    audit_fixture_bytes,
    canonical_json,
)


ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "collector" / "fixtures" / "jra-real-historical-fixture-20260919-20261114.json"


class FundamentalFeatureReadinessAuditTest(unittest.TestCase):
    def test_real_fixture_is_fail_closed_for_f0(self):
        report = audit_fixture(FIXTURE)

        self.assertEqual(SCHEMA, report["schema"])
        self.assertEqual(22, report["race_count"])
        self.assertEqual(307, report["runner_count"])
        self.assertEqual(307, report["active_runner_count"])
        self.assertEqual([], report["unknown_race_fields"])
        self.assertEqual([], report["unknown_runner_fields"])
        self.assertEqual([], report["ready_for_f0_fields"])
        self.assertEqual("BLOCKED_ASOF_EVIDENCE", report["F0_FEATURE_READINESS"])
        self.assertEqual("NOT_AVAILABLE", report["F0_MODEL_STATUS"])
        self.assertEqual("NOT_AVAILABLE", report["STAKE_RULE"])
        self.assertEqual("BLOCKED", report["FORMAL_PAPER_BUY"])
        self.assertEqual("BLOCKED", report["REAL_MONEY"])
        self.assertEqual("DISABLED", report["AUTO_REAL_BET"])

        self.assertIn("final_win_odds", report["forbidden_market_fields"])
        self.assertIn("popularity", report["forbidden_market_fields"])
        self.assertIn("finish_position", report["forbidden_post_race_fields"])
        self.assertIn("finish_status", report["forbidden_post_race_fields"])
        self.assertIn("win_payout", report["forbidden_post_race_fields"])

        self.assertIn("surface", report["candidate_asof_unverified_fields"])
        self.assertIn("distance_m", report["candidate_asof_unverified_fields"])
        self.assertIn("carried_weight_kg", report["candidate_asof_unverified_fields"])
        self.assertIn("sex_age", report["candidate_asof_unverified_fields"])

    def test_fixture_retrieval_does_not_prove_pre_race_asof(self):
        report = audit_fixture(FIXTURE)
        evidence = report["source_asof_evidence"]

        self.assertEqual(0, evidence["card_retrieved_at_or_before_start"])
        self.assertEqual(20, evidence["card_retrieved_after_start"])
        self.assertEqual(2, evidence["card_retrieved_unparseable_or_missing"])
        self.assertEqual(0, evidence["result_retrieved_at_or_before_start"])
        self.assertEqual(20, evidence["result_retrieved_after_start"])
        self.assertEqual(2, evidence["result_retrieved_unparseable_or_missing"])

        # A historical replay fixture can validate parsing/result integrity, but it cannot
        # itself prove that candidate Fundamental values were available at the required
        # prospective as-of time.
        self.assertEqual([], report["ready_for_f0_fields"])

    def test_audit_is_deterministic_for_identical_bytes(self):
        raw = FIXTURE.read_bytes()
        first = audit_fixture_bytes(raw)
        second = audit_fixture_bytes(raw)

        self.assertEqual(first["fixture_sha256"], second["fixture_sha256"])
        self.assertEqual(first["audit_sha256"], second["audit_sha256"])
        self.assertEqual(canonical_json(first), canonical_json(second))

    def test_unknown_fields_are_reported_not_silently_admitted(self):
        payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
        mutated = copy.deepcopy(payload)
        mutated["races"][0]["mystery_future_signal"] = 999
        mutated["races"][0]["runners"][0]["secret_market_feature"] = 1.23

        raw = json.dumps(mutated, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        report = audit_fixture_bytes(raw)

        self.assertEqual(["mystery_future_signal"], report["unknown_race_fields"])
        self.assertEqual(["secret_market_feature"], report["unknown_runner_fields"])
        self.assertNotIn("mystery_future_signal", report["ready_for_f0_fields"])
        self.assertNotIn("secret_market_feature", report["ready_for_f0_fields"])

    def test_candidate_policy_never_misclassifies_market_or_result_fields(self):
        report = audit_fixture(FIXTURE)
        candidates = set(report["candidate_asof_unverified_fields"])
        forbidden = set(report["forbidden_market_fields"]) | set(report["forbidden_post_race_fields"])

        self.assertTrue(candidates)
        self.assertTrue(forbidden)
        self.assertTrue(candidates.isdisjoint(forbidden))
        self.assertTrue(
            {
                "final_win_odds",
                "popularity",
                "finish_position",
                "finish_status",
                "win_payout",
            }.issubset(forbidden)
        )


if __name__ == "__main__":
    unittest.main()
